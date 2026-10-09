#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>

// =============================================================================
// BUTTCRAFT VOLUMETRIC FART GAS
//
// FULLSCREEN RAY MARCHER. THE CPU SIMULATES A SET OF SPHERICAL GAS "PUFFS"
// (POSITION, RADIUS, DENSITY, LOCAL LIGHT LEVEL). THIS SHADER TURNS THEM INTO A
// CONTINUOUS PARTICIPATING MEDIUM:
//
//   1. INTERSECT THE VIEW RAY WITH EVERY PUFF'S BOUNDING SPHERE.
//   2. MARCH THE UNION OF THOSE INTERVALS, CLIPPED BY THE SCENE DEPTH BUFFER.
//   3. BUILD DENSITY FROM A SMOOTH METABALL FIELD ERODED BY TILEABLE 3D
//      PERLIN-WORLEY / WORLEY NOISE, DOMAIN-WARPED FOR SWIRLING WISPS.
//   4. LIGHT EACH SAMPLE WITH A SHORT SHADOW MARCH TOWARD THE SUN/MOON, A
//      DUAL-LOBE HENYEY-GREENSTEIN PHASE FUNCTION, A MULTIPLE-SCATTERING
//      OCTAVE APPROXIMATION, SKY AMBIENT AND BLOCK LIGHT.
//   5. INTEGRATE WITH ENERGY-CONSERVING BEER-LAMBERT STEPS AND APPLY VANILLA
//      FOG. OUTPUT IS PREMULTIPLIED ALPHA.
// =============================================================================

#define MAX_PUFFS 96
#define MAX_HITS 24

layout(std140) uniform FartGasInfo {
    mat4 ViewToWorld;      // VIEW SPACE -> CAMERA-RELATIVE WORLD SPACE (ROTATION ONLY)
    vec4 NoiseOrigin;      // XYZ = CAMERA POSITION WRAPPED TO THE NOISE PERIOD, W = TIME IN SECONDS
    vec4 LightDirection;   // XYZ = WORLD DIRECTION TOWARD SUN/MOON, W = DIRECT LIGHT STRENGTH
    vec4 LightColor;       // RGB = DIRECT LIGHT COLOR
    vec4 AmbientColor;     // RGB = SKY AMBIENT COLOR
    vec4 GasColor;         // RGB = SCATTERING ALBEDO, W = DENSITY MULTIPLIER
    ivec4 Counts;          // X = PUFF COUNT, Y = MAX VIEW STEPS, Z = SHADOW STEPS
    vec4 Puffs[MAX_PUFFS * 2];
    // PUFFS[2I + 0] = CAMERA-RELATIVE CENTER XYZ, RADIUS
    // PUFFS[2I + 1] = DENSITY, SKY LIGHT (0-1), BLOCK LIGHT (0-1), SEED
};

uniform sampler2D NoiseSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec4 unprojectBase;
layout(location = 2) in vec4 unprojectDepth;

layout(location = 0) out vec4 fragColor;

// -----------------------------------------------------------------------------
// TILEABLE 3D NOISE STORED AS A 2D ATLAS: 64 SLICES OF 64x64, EACH WITH A 1px
// WRAPPED GUTTER, LAID OUT IN AN 8x8 GRID (528x528). THE GUTTER LETS HARDWARE
// BILINEAR FILTERING WRAP CORRECTLY; THE THIRD AXIS IS INTERPOLATED HERE.
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
int hitList[MAX_HITS];
int hitCount = 0;

// SMOOTH METABALL FIELD. X = GEOMETRIC COVERAGE (0-1, INDEPENDENT OF HOW
// THIN THE GAS IS), Y = FIELD-WEIGHTED GAS DENSITY. ALSO RETURNS THE WEIGHTED
// SKY LIGHT, BLOCK LIGHT AND NORMALISED HEIGHT INSIDE THE PUFF (FOR AMBIENT
// OCCLUSION). KEEPING SHAPE AND DENSITY SEPARATE MEANS DISSIPATING GAS GETS
// MORE TRANSPARENT INSTEAD OF BEING ERODED INTO ISOLATED DOTS.
vec2 puffField(vec3 p, out vec3 info) {
    float densitySum = 0.0;
    float weightSum = 0.0;
    info = vec3(0.0);

    for (int k = 0; k < MAX_HITS; k++) {
        if (k >= hitCount) {
            break;
        }

        int i = hitList[k] * 2;
        vec4 shape = Puffs[i];
        vec3 d = p - shape.xyz;
        float d2 = dot(d, d) / (shape.w * shape.w);
        if (d2 >= 1.0) {
            continue;
        }

        vec4 data = Puffs[i + 1];
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

vec3 warpOffset(vec3 wp, float time) {
    vec4 n = noise3(wp / WARP_SCALE + vec3(0.0, -0.0045, 0.002) * time);
    return (vec3(n.a, n.g, n.b) - 0.5) * 2.2;
}

// FULL-QUALITY DENSITY FOR VIEW SAMPLES.
float gasDensity(vec3 p, vec2 field, float height) {
    vec3 wp = p + NoiseOrigin.xyz;
    float time = NoiseOrigin.w;
    vec3 warp = warpOffset(wp, time);

    // BASE SHAPE: ERODE THE SMOOTH FIELD WITH LOW-FREQUENCY PERLIN-WORLEY.
    vec4 shapeNoise = noise3((wp + warp) / SHAPE_SCALE + vec3(0.003, 0.011, -0.004) * time);
    float shape = shapeNoise.r * 0.55 + shapeNoise.g * 0.3 + shapeNoise.b * 0.15;
    // EVEN FULLY COVERED REGIONS KEEP SOME EROSION SO THE CORE NEVER LOOKS LIKE SMOOTH FOG.
    float density = remap(shape, 1.0 - field.x * 0.74, 1.0) * field.x;
    if (density <= 0.0) {
        return 0.0;
    }

    // DETAIL EROSION: BILLOWY ON TOP, WISPY UNDERNEATH, STRONGEST AT THE EDGES.
    vec4 detailNoise = noise3((wp + warp * 0.55) / DETAIL_SCALE + vec3(-0.012, 0.035, 0.009) * time);
    float detail = detailNoise.g * 0.6 + detailNoise.b * 0.4;
    detail = mix(1.0 - detail, detail, clamp(height * 1.6, 0.0, 1.0));
    density = remap(density, detail * 0.55 * (1.0 - density * 0.45), 1.0);

    return density * field.y * GasColor.w;
}

// CHEAPER DENSITY FOR SHADOW SAMPLES (NO DETAIL OCTAVE).
float gasDensityLow(vec3 p) {
    vec3 info;
    vec2 field = puffField(p, info);
    if (field.x <= 0.002) {
        return 0.0;
    }

    vec3 wp = p + NoiseOrigin.xyz;
    float time = NoiseOrigin.w;
    vec4 shapeNoise = noise3(wp / SHAPE_SCALE + vec3(0.003, 0.011, -0.004) * time);
    float shape = shapeNoise.r * 0.55 + shapeNoise.g * 0.3 + shapeNoise.b * 0.15;
    return remap(shape, 1.0 - field.x * 0.74, 1.0) * field.x * field.y * GasColor.w;
}

// -----------------------------------------------------------------------------

float deviceToNdcDepth(float deviceDepth) {
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    return deviceDepth;
    #else
    return deviceDepth * 2.0 - 1.0;
    #endif
}

// VIEW-SPACE POSITION AT THIS PIXEL FOR A DEVICE DEPTH (REVERSED-Z: 1 = NEAR, 0 = FAR).
vec3 unprojectView(float deviceDepth) {
    vec4 h = unprojectBase + deviceToNdcDepth(deviceDepth) * unprojectDepth;
    return h.xyz / h.w;
}

void main() {
    int puffCount = min(Counts.x, MAX_PUFFS);
    if (puffCount <= 0) {
        discard;
    }

    // RAY FROM THE NEAR PLANE THROUGH A FAR POINT. BOTH ARE REAL UNPROJECTED POINTS,
    // SO THE RAY STAYS CORRECT WHILE VIEW BOBBING OFFSETS THE EYE.
    vec3 nearView = unprojectView(1.0);
    vec3 farView = unprojectView(0.001);
    mat3 viewToWorld = mat3(ViewToWorld);
    vec3 ro = viewToWorld * nearView;
    vec3 rd = normalize(viewToWorld * (farView - nearView));

    // DISTANCE ALONG THE RAY TO THE OPAQUE SCENE (SKY = 0 = INFINITELY FAR).
    float deviceDepth = texture(DepthSampler, texCoord).r;
    float sceneT = 1e9;
    if (deviceDepth > 0.0) {
        sceneT = max(dot(viewToWorld * unprojectView(deviceDepth) - ro, rd), 0.0);
    }

    // 1. BROAD PHASE: RAY / SPHERE AGAINST EVERY PUFF.
    float tStart = 1e9;
    float tEnd = 0.0;
    for (int i = 0; i < MAX_PUFFS; i++) {
        if (i >= puffCount || hitCount >= MAX_HITS) {
            break;
        }

        vec4 shape = Puffs[i * 2];
        vec3 toCenter = shape.xyz - ro;
        float b = dot(toCenter, rd);
        float c = dot(toCenter, toCenter) - shape.w * shape.w;
        float h = b * b - c;
        if (h <= 0.0) {
            continue;
        }

        h = sqrt(h);
        float t0 = max(b - h, 0.0);
        float t1 = b + h;
        if (t1 <= 0.0 || t0 >= sceneT) {
            continue;
        }

        hitList[hitCount++] = i;
        tStart = min(tStart, t0);
        tEnd = max(tEnd, t1);
    }

    tEnd = min(tEnd, sceneT);
    if (hitCount == 0 || tEnd <= tStart) {
        discard;
    }

    // 2. MARCH SETUP.
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
    float span = tEnd - tStart;
    int steps = int(clamp(ceil(span / 0.05), 12.0, float(max(Counts.y, 12))));
    float dt = span / float(steps);

    vec3 lightDir = normalize(LightDirection.xyz);
    float cosTheta = dot(rd, lightDir);
    float directStrength = LightDirection.w;
    int shadowSteps = max(Counts.z, 1);

    float transmittance = 1.0;
    vec3 radiance = vec3(0.0);
    float weightedDistance = 0.0;
    float weightTotal = 0.0;

    float t = tStart + dt * jitter;
    for (int s = 0; s < 256; s++) {
        if (s >= steps || transmittance < 0.01) {
            break;
        }

        vec3 p = ro + rd * t;
        vec3 info;
        vec2 field = puffField(p, info);

        if (field.x > 0.002) {
            float density = gasDensity(p, field, info.z);

            if (density > 0.001) {
                float sigmaT = density * EXTINCTION;

                // 3. SHADOW MARCH TOWARD THE LIGHT WITH GEOMETRICALLY GROWING STEPS.
                float opticalDepth = 0.0;
                if (directStrength > 0.001) {
                    float lightStep = 0.09;
                    float lightT = lightStep * (0.5 + jitter * 0.5);
                    for (int l = 0; l < 8; l++) {
                        if (l >= shadowSteps) {
                            break;
                        }

                        opticalDepth += gasDensityLow(p + lightDir * lightT) * lightStep;
                        lightStep *= 1.8;
                        lightT += lightStep;
                    }

                    opticalDepth *= EXTINCTION;
                }

                // MULTIPLE-SCATTERING OCTAVES: EACH OCTAVE ATTENUATES LESS, SCATTERS LESS
                // AND IS MORE ISOTROPIC, BRIGHTENING THICK GAS LIKE REAL SMOKE.
                float direct = 0.0;
                float a = 1.0;
                float bScale = 1.0;
                float cScale = 1.0;
                for (int o = 0; o < 3; o++) {
                    direct += bScale * exp(-opticalDepth * a) * phase(cosTheta, cScale);
                    a *= 0.42;
                    bScale *= 0.5;
                    cScale *= 0.5;
                }
                direct *= 1.0 / 1.75;

                // POWDER TERM: DARKENS THIN EDGES FACING AWAY FROM THE LIGHT.
                float powder = 1.0 - exp(-sigmaT * 1.6);
                direct *= mix(1.0, powder * 1.6, 0.35 * (1.0 - cosTheta) * 0.5);

                float skyLight = info.x;
                float blockLight = info.y;
                float height = info.z;

                // DIRECT LIGHT IS GATED BY THE PUFF'S SKY ACCESS (NO SUN IN CAVES).
                float sunVisibility = directStrength * smoothstep(0.35, 0.95, skyLight);
                vec3 directLight = LightColor.rgb * direct * sunVisibility;

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
