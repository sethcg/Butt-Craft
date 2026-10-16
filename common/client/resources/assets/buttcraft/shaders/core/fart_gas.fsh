#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>
#include <buttcraft:fart_gas_common.glsl>

// =============================================================================
// BUTTCRAFT VOLUMETRIC FART GAS
//
// RAY MARCHER, USUALLY AT REDUCED RESOLUTION (UPSCALED BY fart_gas_composite.fsh). THE
// CPU SIMULATES A SET OF SPHERICAL GAS "PUFFS" (POSITION, RADIUS, DENSITY, LOCAL
// LIGHT LEVEL). THIS SHADER TURNS THEM INTO A CONTINUOUS PARTICIPATING MEDIUM:
//
//   1. INTERSECT THE VIEW RAY WITH EVERY PUFF'S BOUNDING SPHERE AND MERGE THE HITS
//      INTO DISJOINT SPANS. ALSO COLLECT NEARBY PUFFS THAT CAN ONLY SHADOW THE RAY.
//   2. MARCH ONLY THOSE SPANS (GAPS BETWEEN SEPARATE CLOUDS COST NO STEPS), CLIPPED
//      BY THE SCENE DEPTH BUFFER.
//   3. BUILD DENSITY FROM A SMOOTH METABALL FIELD ERODED BY TILEABLE 3D
//      PERLIN-WORLEY / WORLEY NOISE, DOMAIN-WARPED FOR SWIRLING WISPS.
//   4. LIGHT EACH SAMPLE WITH A SHORT SHADOW MARCH TOWARD THE SUN/MOON, A
//      DUAL-LOBE HENYEY-GREENSTEIN PHASE FUNCTION, A MULTIPLE-SCATTERING
//      OCTAVE APPROXIMATION, SKY AMBIENT AND BLOCK LIGHT.
//   5. INTEGRATE WITH ENERGY-CONSERVING BEER-LAMBERT STEPS AND APPLY VANILLA
//      FOG. OUTPUT IS PREMULTIPLIED ALPHA.
// =============================================================================

// LOOP BOUNDS: AT LEAST FartGasSettings.MAX_VIEW_STEPS / MAX_SHADOW_STEPS.
#define MAX_VIEW_STEPS 128
#define MAX_SHADOW_STEPS 8

// PUFFS THE VIEW RAY PASSES THROUGH, AND THE TOTAL INCLUDING PUFFS THAT ONLY SHADOW IT.
#define MAX_VIEW_HITS 24
#define MAX_HITS 32

uniform sampler2D NoiseSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec4 unprojectBase;
layout(location = 1) in vec4 unprojectDepth;

layout(location = 0) out vec4 fragColor;

// -----------------------------------------------------------------------------
// TILEABLE 3D NOISE STORED AS A 2D ATLAS: 64 SLICES OF 64x64, EACH WITH A 1px
// WRAPPED GUTTER, LAID OUT IN AN 8x8 GRID (528x528). THE GUTTER LETS HARDWARE
// BILINEAR FILTERING WRAP CORRECTLY; THE THIRD AXIS IS INTERPOLATED HERE (THE GPU
// API ONLY HAS 2D AND CUBEMAP TEXTURES, SO NO HARDWARE 3D SAMPLING).
//   R = PERLIN-WORLEY (BASE SHAPE)
//   G = WORLEY FBM, LOW FREQUENCY
//   B = WORLEY FBM, HIGH FREQUENCY
//   A = PERLIN FBM (USED FOR DOMAIN WARPING)
// -----------------------------------------------------------------------------
const float NOISE_SIZE = 64.0;
const float NOISE_TILE = 66.0;
const float NOISE_GRID = 8.0;
const float NOISE_ATLAS = 528.0;

// WORLD-SPACE TILING PERIODS (BLOCKS). THE CPU WRAPS THE CAMERA POSITION BY
// THEIR LEAST COMMON MULTIPLE (12) SO THE NOISE STAYS LOCKED TO THE WORLD.
const float WARP_SCALE = 4.0;
const float SHAPE_SCALE = 1.5;
const float DETAIL_SCALE = 0.5;

// NOISE SCROLL SPEEDS (PERIODS PER SECOND). THE CPU WRAPS TIME AT 2000 SECONDS, WHICH
// TIMES EACH OF THESE IS A WHOLE NUMBER, SO THE WRAP IS SEAMLESS. KEEP IT THAT WAY.
const vec3 WARP_SCROLL = vec3(0.0, -0.0045, 0.002);
const vec3 SHAPE_SCROLL = vec3(0.003, 0.011, -0.004);
const vec3 DETAIL_SCROLL = vec3(-0.012, 0.035, 0.009);

// DISTANCE LOD (BLOCKS): VIEW STEPS GROW PAST STEP_LOD_START, AND THE DETAIL
// OCTAVE FADES OUT BETWEEN DETAIL_LOD_START AND DETAIL_LOD_END.
const float BASE_STEP = 0.05;
const float STEP_LOD_START = 8.0;
const float MAX_STEP_SCALE = 4.0;
const float DETAIL_LOD_START = 16.0;
const float DETAIL_LOD_END = 32.0;

// STOP MARCHING ONCE THIS LITTLE OF THE BACKGROUND STILL SHOWS THROUGH.
const float MIN_TRANSMITTANCE = 0.03;

// SHADOW RAYS REACH THIS FAR (BLOCKS) WITH GEOMETRICALLY GROWING STEPS, WHATEVER
// THE STEP COUNT, SO LOWER QUALITY GIVES COARSER SHADOWS RATHER THAN SHORTER ONES.
const float SHADOW_REACH = 1.07;
const float SHADOW_GROWTH = 1.8;

// OPTICAL PROPERTIES (PER BLOCK AT DENSITY 1).
const float EXTINCTION = 4.0;
const vec3 BLOCK_LIGHT_TINT = vec3(1.0, 0.78, 0.52);

vec2 atlasTile(float slice) {
    return vec2(mod(slice, NOISE_GRID), floor(slice / NOISE_GRID)) * (NOISE_TILE / NOISE_ATLAS);
}

vec4 noise3(vec3 p) {
    p = fract(p);
    float z = p.z * NOISE_SIZE - 0.5;
    float zi = floor(z);
    float f = z - zi;
    float s0 = mod(zi + NOISE_SIZE, NOISE_SIZE);
    float s1 = mod(zi + 1.0, NOISE_SIZE);
    vec2 inTile = (1.0 + p.xy * NOISE_SIZE) / NOISE_ATLAS;
    vec4 a = texture(NoiseSampler, atlasTile(s0) + inTile);
    vec4 b = texture(NoiseSampler, atlasTile(s1) + inTile);
    return mix(a, b, f);
}

float remap(float v, float lo, float hi) {
    return clamp((v - lo) / max(hi - lo, 1e-4), 0.0, 1.0);
}

// NORMALISED HENYEY-GREENSTEIN (ISOTROPIC == 1).
float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / pow(max(1.0 + g2 - 2.0 * g * cosTheta, 1e-4), 1.5);
}

float phase(float cosTheta, float eccentricity) {
    return mix(henyeyGreenstein(cosTheta, 0.62 * eccentricity), henyeyGreenstein(cosTheta, -0.22 * eccentricity), 0.28);
}

// MINECRAFT'S LIGHT LEVEL -> BRIGHTNESS CURVE.
float brightnessCurve(float level) {
    return level / (4.0 - 3.0 * level);
}

// INTERLEAVED GRADIENT NOISE: CHEAP, WELL-DISTRIBUTED PER-PIXEL JITTER.
float interleavedGradientNoise(vec2 pixel) {
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

// -----------------------------------------------------------------------------
// PUFF FIELD
// -----------------------------------------------------------------------------

// hitList[0, viewHitCount) ARE ON THE VIEW RAY. hitList[viewHitCount, hitCount) ARE OFF IT
// BUT WITHIN SHADOW_REACH, SO THEY ONLY MATTER TO SHADOW SAMPLES.
int hitList[MAX_HITS];
int viewHitCount = 0;
int hitCount = 0;

// THE VIEW RAY'S PUFF INTERVALS, SORTED AND MERGED INTO DISJOINT SPANS.
float spanStart[MAX_VIEW_HITS];
float spanEnd[MAX_VIEW_HITS];
int spanCount = 0;

// SMOOTH METABALL FIELD OVER THE VIEW-RAY PUFFS. X = GEOMETRIC COVERAGE (0-1,
// INDEPENDENT OF HOW THIN THE GAS IS), Y = FIELD-WEIGHTED GAS DENSITY. ALSO RETURNS THE
// WEIGHTED SKY LIGHT, BLOCK LIGHT AND NORMALISED HEIGHT INSIDE THE PUFF (FOR AMBIENT
// OCCLUSION). KEEPING SHAPE AND DENSITY SEPARATE MEANS DISSIPATING GAS GETS
// MORE TRANSPARENT INSTEAD OF BEING ERODED INTO ISOLATED DOTS.
vec2 puffField(vec3 p, out vec3 info) {
    float densitySum = 0.0;
    float weightSum = 0.0;
    info = vec3(0.0);

    for (int k = 0; k < MAX_VIEW_HITS; k++) {
        if (k >= viewHitCount) {
            break;
        }

        int i = hitList[k] * 2;
        vec4 shape = Puffs[i];
        vec4 data = Puffs[i + 1];
        vec3 d = p - shape.xyz;
        float d2 = dot(d, d) * data.w;
        if (d2 >= 1.0) {
            continue;
        }

        float w = 1.0 - d2;
        w = w * w;
        densitySum += w * data.x;
        weightSum += w;
        info += w * vec3(data.y, data.z, clamp(d.y / shape.w * 0.5 + 0.5, 0.0, 1.0));
    }

    float inverseWeight = 1.0 / max(weightSum, 1e-4);
    info *= inverseWeight;
    return vec2(clamp(weightSum * 1.35, 0.0, 1.0), densitySum * inverseWeight);
}

// THE SAME FIELD WITHOUT THE LIGHTING INFO, OVER EVERY PUFF THAT CAN SHADOW THE RAY.
vec2 puffCoverage(vec3 p) {
    float densitySum = 0.0;
    float weightSum = 0.0;

    for (int k = 0; k < MAX_HITS; k++) {
        if (k >= hitCount) {
            break;
        }

        int i = hitList[k] * 2;
        vec4 data = Puffs[i + 1];
        vec3 d = p - Puffs[i].xyz;
        float d2 = dot(d, d) * data.w;
        if (d2 >= 1.0) {
            continue;
        }

        float w = 1.0 - d2;
        w = w * w;
        densitySum += w * data.x;
        weightSum += w;
    }

    return vec2(clamp(weightSum * 1.35, 0.0, 1.0), densitySum / max(weightSum, 1e-4));
}

vec3 warpOffset(vec3 wp, float time) {
    vec4 n = noise3(wp / WARP_SCALE + WARP_SCROLL * time);
    return (vec3(n.a, n.g, n.b) - 0.5) * 2.2;
}

// BASE SHAPE: ERODE THE SMOOTH FIELD WITH LOW-FREQUENCY PERLIN-WORLEY. EVEN FULLY
// COVERED REGIONS KEEP SOME EROSION SO THE CORE NEVER LOOKS LIKE SMOOTH FOG.
float baseShape(vec3 wp, vec3 warp, float coverage, float time) {
    vec4 shapeNoise = noise3((wp + warp) / SHAPE_SCALE + SHAPE_SCROLL * time);
    float shape = shapeNoise.r * 0.55 + shapeNoise.g * 0.3 + shapeNoise.b * 0.15;
    return remap(shape, 1.0 - coverage * 0.74, 1.0) * coverage;
}

// FULL-QUALITY DENSITY FOR VIEW SAMPLES (wp = WRAPPED WORLD POSITION). DETAIL (0-1)
// SCALES THE HIGH-FREQUENCY EROSION; AT 0 ITS NOISE FETCH IS SKIPPED.
float gasDensity(vec3 wp, vec3 warp, float time, vec2 field, float height, float detailAmount) {
    float density = baseShape(wp, warp, field.x, time);
    if (density <= 0.0) {
        return 0.0;
    }

    if (detailAmount > 0.0) {
        // DETAIL EROSION: BILLOWY ON TOP, WISPY UNDERNEATH, STRONGEST AT THE EDGES.
        vec4 detailNoise = noise3((wp + warp * 0.55) / DETAIL_SCALE + DETAIL_SCROLL * time);
        float detail = detailNoise.g * 0.6 + detailNoise.b * 0.4;
        detail = mix(1.0 - detail, detail, clamp(height * 1.6, 0.0, 1.0));
        density = mix(density, remap(density, detail * 0.55 * (1.0 - density * 0.45), 1.0), detailAmount);
    }

    return density * field.y * GasColor.w;
}

// CHEAPER DENSITY FOR SHADOW SAMPLES: NO DETAIL OCTAVE, AND IT REUSES THE VIEW SAMPLE'S
// DOMAIN WARP INSTEAD OF FETCHING ITS OWN. THE WARP CHANGES OVER WARP_SCALE (4 BLOCKS),
// WELL BEYOND SHADOW_REACH, SO SHADOWS STILL FOLLOW THE VISIBLE (WARPED) SHAPES.
float shadowDensity(vec3 p, vec3 warp, float time) {
    vec2 field = puffCoverage(p);
    if (field.x <= 0.002) {
        return 0.0;
    }

    return baseShape(p + NoiseOrigin.xyz, warp, field.x, time) * field.y * GasColor.w;
}

// -----------------------------------------------------------------------------

vec3 unprojectView(float deviceDepth) {
    return unproject(unprojectBase, unprojectDepth, deviceDepth);
}

bool isViewHit(uvec2 mask, int i) {
    return (mask[i >> 5] & (1u << uint(i & 31))) != 0u;
}

void main() {
    int puffCount = min(Counts.x, MAX_PUFFS);

    // RAY FROM THE NEAR PLANE THROUGH A FAR POINT. BOTH ARE REAL UNPROJECTED POINTS,
    // SO THE RAY STAYS CORRECT WHILE VIEW BOBBING OFFSETS THE EYE.
    vec3 nearView = unprojectView(1.0);
    vec3 farView = unprojectView(0.001);
    mat3 viewToWorld = mat3(ViewToWorld);
    vec3 ro = viewToWorld * nearView;
    vec3 rd = normalize(viewToWorld * (farView - nearView));

    // DISTANCE ALONG THE RAY TO THE OPAQUE SCENE (SKY = 0 = INFINITELY FAR). AT REDUCED
    // RESOLUTION EACH PIXEL READS THE FULL-RESOLUTION DEPTH AT THE CENTER OF THE BLOCK IT
    // COVERS, THE SAME TEXEL THE COMPOSITE PASS COMPARES AGAINST WHEN UPSCALING.
    int divisor = max(Counts.w, 1);
    ivec2 depthCoord = min(ivec2(gl_FragCoord.xy) * divisor + divisor / 2, textureSize(DepthSampler, 0) - 1);
    float deviceDepth = texelFetch(DepthSampler, depthCoord, 0).r;
    float sceneT = 1e9;
    if (deviceDepth > 0.0) {
        sceneT = max(dot(viewToWorld * unprojectView(deviceDepth) - ro, rd), 0.0);
    }

    // 1. BROAD PHASE: RAY / SPHERE AGAINST EVERY PUFF. THE CPU UPLOADS THE DENSEST FIRST,
    // SO IF THE LIST FILLS UP IT'S THE FAINTEST PUFFS THAT GET LEFT OUT.
    uvec2 viewMask = uvec2(0u);
    for (int i = 0; i < MAX_PUFFS; i++) {
        if (i >= puffCount || viewHitCount >= MAX_VIEW_HITS) {
            break;
        }

        vec4 shape = Puffs[i * 2];
        vec3 toCenter = shape.xyz - ro;
        float b = dot(toCenter, rd);
        float h = b * b - (dot(toCenter, toCenter) - shape.w * shape.w);
        if (h <= 0.0) {
            continue;
        }

        h = sqrt(h);
        float t0 = max(b - h, 0.0);
        float t1 = min(b + h, sceneT);
        if (t1 <= t0) {
            continue;
        }

        spanStart[viewHitCount] = t0;
        spanEnd[viewHitCount] = t1;
        hitList[viewHitCount++] = i;
        viewMask[i >> 5] |= 1u << uint(i & 31);
    }

    if (viewHitCount == 0) {
        discard;
    }

    // SORT THE INTERVALS BY START (INSERTION SORT; THERE ARE FEW) AND MERGE OVERLAPS.
    for (int a = 1; a < MAX_VIEW_HITS; a++) {
        if (a >= viewHitCount) {
            break;
        }

        float s = spanStart[a];
        float e = spanEnd[a];
        int j = a;
        for (; j > 0; j--) {
            if (spanStart[j - 1] <= s) {
                break;
            }

            spanStart[j] = spanStart[j - 1];
            spanEnd[j] = spanEnd[j - 1];
        }

        spanStart[j] = s;
        spanEnd[j] = e;
    }

    spanCount = 1;
    for (int a = 1; a < MAX_VIEW_HITS; a++) {
        if (a >= viewHitCount) {
            break;
        }

        if (spanStart[a] <= spanEnd[spanCount - 1]) {
            spanEnd[spanCount - 1] = max(spanEnd[spanCount - 1], spanEnd[a]);
        } else {
            spanStart[spanCount] = spanStart[a];
            spanEnd[spanCount] = spanEnd[a];
            spanCount++;
        }
    }

    float covered = 0.0;
    for (int a = 0; a < MAX_VIEW_HITS; a++) {
        if (a >= spanCount) {
            break;
        }

        covered += spanEnd[a] - spanStart[a];
    }

    float tStart = spanStart[0];
    float tEnd = spanEnd[spanCount - 1];

    vec3 lightDir = LightDirection.xyz;
    float directStrength = LightDirection.w;

    // SHADOW-ONLY PUFFS: OFF THE VIEW RAY BUT CLOSE ENOUGH TO THE MARCHED PART OF IT THAT A
    // SHADOW SAMPLE CAN LAND INSIDE THEM. WITHOUT THESE, SHADOWS WOULD DEPEND ON WHICH
    // PUFFS HAPPEN TO BE IN VIEW AND CHANGE AS THE CAMERA TURNS.
    hitCount = viewHitCount;
    if (directStrength > 0.0) {
        for (int i = 0; i < MAX_PUFFS; i++) {
            if (i >= puffCount || hitCount >= MAX_HITS) {
                break;
            }

            if (isViewHit(viewMask, i)) {
                continue;
            }

            vec4 shape = Puffs[i * 2];
            float reach = shape.w + SHADOW_REACH;
            vec3 toCenter = shape.xyz - ro;
            float b = dot(toCenter, rd);
            float h = b * b - (dot(toCenter, toCenter) - reach * reach);
            if (h <= 0.0) {
                continue;
            }

            h = sqrt(h);
            if (b + h <= tStart || b - h >= tEnd) {
                continue;
            }

            hitList[hitCount++] = i;
        }
    }

    // 2. MARCH SETUP. THE STEP BUDGET IS SPREAD OVER THE COVERED LENGTH ONLY, NOT THE GAPS.
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
    // DISTANT GAS COVERS FEW PIXELS AND DOESN'T NEED FINE STEPS.
    float stepSize = BASE_STEP * clamp(tStart / STEP_LOD_START, 1.0, MAX_STEP_SCALE);
    int maxSteps = clamp(Counts.y, 12, MAX_VIEW_STEPS);
    int steps = int(clamp(ceil(covered / stepSize), 12.0, float(maxSteps)));
    float dt = covered / float(steps);

    float time = NoiseOrigin.w;
    float cosTheta = dot(rd, lightDir);
    int shadowSteps = clamp(Counts.z, 1, MAX_SHADOW_STEPS);
    float firstShadowStep = SHADOW_REACH * (SHADOW_GROWTH - 1.0) / (pow(SHADOW_GROWTH, float(shadowSteps)) - 1.0);

    // THE PHASE FUNCTION ONLY DEPENDS ON THE VIEW/LIGHT ANGLE, SO EVALUATE THE
    // MULTIPLE-SCATTERING OCTAVES' LOBES ONCE PER PIXEL INSTEAD OF PER SAMPLE.
    float phase0 = phase(cosTheta, 1.0);
    float phase1 = phase(cosTheta, 0.5) * 0.5;
    float phase2 = phase(cosTheta, 0.25) * 0.25;
    float powderMix = 0.35 * (1.0 - cosTheta) * 0.5;

    // SHADOWS CHANGE SLOWLY ALONG THE RAY, SO THE SHADOW MARCH RUNS ON EVERY OTHER
    // LIT SAMPLE AND THE SAMPLE IN BETWEEN REUSES ITS OPTICAL DEPTH.
    float cachedOpticalDepth = 0.0;
    int litSamples = 0;

    float transmittance = 1.0;
    vec3 radiance = vec3(0.0);
    float weightedDistance = 0.0;
    float weightTotal = 0.0;

    int span = 0;
    float t = tStart + dt * jitter;
    for (int s = 0; s < MAX_VIEW_STEPS; s++) {
        if (s >= steps || transmittance < MIN_TRANSMITTANCE) {
            break;
        }

        // STEPPING PAST THE END OF A SPAN CARRIES THE REMAINDER INTO THE NEXT ONE, SO SAMPLES
        // STAY EVENLY SPACED ALONG THE GAS AND THE GAPS BETWEEN SPANS ARE SKIPPED.
        for (int g = 0; g < MAX_VIEW_HITS; g++) {
            if (t <= spanEnd[span] || span + 1 >= spanCount) {
                break;
            }

            t = spanStart[span + 1] + (t - spanEnd[span]);
            span++;
        }

        vec3 p = ro + rd * t;
        vec3 info;
        vec2 field = puffField(p, info);

        if (field.x > 0.002) {
            vec3 wp = p + NoiseOrigin.xyz;
            vec3 warp = warpOffset(wp, time);
            float detailAmount = 1.0 - smoothstep(DETAIL_LOD_START, DETAIL_LOD_END, t);
            float density = gasDensity(wp, warp, time, field, info.z, detailAmount);

            if (density > 0.001) {
                float sigmaT = density * EXTINCTION;

                float skyLight = info.x;
                float blockLight = info.y;
                float height = info.z;

                // DIRECT LIGHT IS GATED BY THE PUFF'S SKY ACCESS (NO SUN IN CAVES).
                float sunVisibility = directStrength * smoothstep(0.35, 0.95, skyLight);
                vec3 directLight = vec3(0.0);

                // 3. SHADOW MARCH TOWARD THE LIGHT WITH GEOMETRICALLY GROWING STEPS.
                // SKIPPED ENTIRELY WHEN NO DIRECT LIGHT REACHES THIS SAMPLE.
                if (sunVisibility > 0.001) {
                    if ((litSamples & 1) == 0) {
                        cachedOpticalDepth = 0.0;
                        float lightStep = firstShadowStep;
                        float lightT = lightStep * (0.5 + jitter * 0.5);
                        for (int l = 0; l < MAX_SHADOW_STEPS; l++) {
                            if (l >= shadowSteps) {
                                break;
                            }

                            cachedOpticalDepth += shadowDensity(p + lightDir * lightT, warp, time) * lightStep;
                            lightStep *= SHADOW_GROWTH;
                            lightT += lightStep;
                        }

                        cachedOpticalDepth *= EXTINCTION;
                    }

                    litSamples++;
                    float opticalDepth = cachedOpticalDepth;

                    // MULTIPLE-SCATTERING OCTAVES: EACH OCTAVE ATTENUATES LESS, SCATTERS LESS
                    // AND IS MORE ISOTROPIC, BRIGHTENING THICK GAS LIKE REAL SMOKE.
                    float direct = exp(-opticalDepth) * phase0
                        + exp(-opticalDepth * 0.42) * phase1
                        + exp(-opticalDepth * 0.1764) * phase2;
                    direct *= 1.0 / 1.75;

                    // POWDER TERM: DARKENS THIN EDGES FACING AWAY FROM THE LIGHT.
                    float powder = 1.0 - exp(-sigmaT * 1.6);
                    direct *= mix(1.0, powder * 1.6, powderMix);

                    directLight = LightColor.rgb * direct * sunVisibility;
                }

                // AMBIENT: SKY LIGHT WITH HEIGHT-BASED OCCLUSION + WARM BLOCK LIGHT.
                float ambientOcclusion = mix(0.45, 1.0, height) * mix(0.6, 1.0, exp(-density * 1.5));
                vec3 ambient = AmbientColor.rgb * brightnessCurve(skyLight) * ambientOcclusion;
                ambient += BLOCK_LIGHT_TINT * brightnessCurve(blockLight) * 0.9 * ambientOcclusion;
                ambient += vec3(0.012, 0.014, 0.010);

                // DENSE CORES ABSORB MORE BLUE: YELLOW-BROWN IN THE MIDDLE, PALE GREEN AT THE EDGES.
                vec3 albedo = mix(GasColor.rgb, GasColor.rgb * vec3(0.82, 0.72, 0.46), clamp(density * 0.7, 0.0, 1.0));
                vec3 inScatter = albedo * (directLight + ambient);

                // 4. ENERGY-CONSERVING INTEGRATION OF THE STEP.
                float stepTransmittance = exp(-sigmaT * dt);
                float absorbed = transmittance * (1.0 - stepTransmittance);
                radiance += inScatter * absorbed;
                weightedDistance += t * absorbed;
                weightTotal += absorbed;
                transmittance *= stepTransmittance;
            }
        }

        t += dt;
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 0.003) {
        discard;
    }

    // 5. VANILLA FOG AT THE OPACITY-WEIGHTED DEPTH OF THE GAS.
    float fogDistance = weightedDistance / max(weightTotal, 1e-4);
    vec3 fogPosition = ro + rd * fogDistance;
    float fogValue = total_fog_value(
        fog_spherical_distance(fogPosition),
        fog_cylindrical_distance(fogPosition),
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd
    );
    radiance = mix(radiance, FogColor.rgb * alpha, fogValue * FogColor.a);

    // DITHER TO HIDE 8-BIT BANDING IN SMOOTH GRADIENTS.
    radiance += (jitter - 0.5) / 255.0;

    fragColor = vec4(max(radiance, vec3(0.0)), alpha);
}
