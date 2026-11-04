#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>
#include <buttcraft:fart_gas_common.glsl>

// TURNS THE CPU'S SPHERICAL PUFFS INTO A CONTINUOUS PARTICIPATING MEDIUM:
//
//   1. INTERSECT THE VIEW RAY WITH THE PUFFS THAT CAN REACH THIS PIXEL, MERGING THE HITS INTO
//      DISJOINT SPANS. ALSO COLLECT NEARBY PUFFS THAT CAN ONLY SHADOW THE RAY
//   2. MARCH ONLY THOSE SPANS, CLIPPED BY SCENE DEPTH
//   3. DENSITY: A SMOOTH METABALL FIELD, FADED AT THE WALLS AND ERODED BY DOMAIN-WARPED NOISE
//   4. LIGHT: A SHORT SHADOW MARCH FOR THE NOISE'S DETAIL, THE CPU'S PER-PUFF OPTICAL DEPTHS FOR
//      THE REST OF THE CLOUD, A DUAL-LOBE PHASE FUNCTION AND MULTIPLE-SCATTERING OCTAVES
//   5. INTEGRATE WITH BEER-LAMBERT STEPS AND APPLY VANILLA FOG. OUTPUT IS PREMULTIPLIED ALPHA
//
// IT ALSO WRITES THE DISTANCES THE LATER PASSES NEED, AND WRITES EMPTY PIXELS INSTEAD OF DISCARDING

// AT LEAST FartGasUniforms.VIEW_STEPS / SHADOW_STEPS
#define MAX_VIEW_STEPS 128
#define MAX_SHADOW_STEPS 8

// MAX_HITS ALSO COUNTS PUFFS THAT ONLY SHADOW THE RAY, AND MUST BE A MULTIPLE OF 4 (SEE hitWords)
#define MAX_VIEW_HITS 20
#define MAX_HITS 24

uniform sampler2D WarpSampler;
uniform sampler2D ShapeSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec4 unprojectBase;
layout(location = 1) in vec4 unprojectDepth;
// NDC MIN XY, MAX XY
layout(location = 2) flat in vec4 runBounds[MAX_RECTS];

layout(location = 0) out vec4 fragColor;
// X = VIEW DISTANCE TO THE SCENE THIS PIXEL MARCHED AGAINST (FOR THE UPSCALE)
// Y = DISTANCE ALONG THE RAY TO THE GAS (FOR THE REPROJECTION, AND SO THE UPSCALE CAN TELL GAS IN
// FRONT OF BOTH SURFACES FROM GAS A NEARER ONE HIDES)
layout(location = 1) out vec2 gasDistance;

// TILEABLE 3D NOISE AS 2D ATLASES OF SLICES (THE GPU API HAS NO 3D TEXTURES), EACH WITH A 1px WRAPPED
// GUTTER FOR BILINEAR FILTERING. EVERY TEXEL ALSO HOLDS THE NEXT SLICE, SO ONE FETCH INTERPOLATES Z
//
// ShapeSampler: 64 SLICES OF 64x64 IN AN 8x8 GRID (528x528)
//   R / G = SHAPE  (PERLIN-WORLEY 0.55 + WORLEY LOW 0.3 + WORLEY HIGH 0.15) AT Z / Z + 1
//   B / A = DETAIL (WORLEY LOW 0.6 + WORLEY HIGH 0.4) AT Z / Z + 1
//
// WarpSampler: 32 SLICES OF 32x32 IN AN 8x4 GRID (272x136)
//   R / G = PERLIN FBM AT Z / Z + 1
//   B / A = WORLEY FBM (LOW) AT Z / Z + 1
const float SHAPE_SIZE = 64.0;
const vec2 SHAPE_GRID = vec2(8.0, 8.0);
const float WARP_SIZE = 32.0;
const vec2 WARP_GRID = vec2(8.0, 4.0);

// BLOCKS. THE CPU WRAPS THE CAMERA POSITION BY THEIR LCM (12) SO THE NOISE STAYS LOCKED TO THE WORLD
const float WARP_SCALE = 4.0;
const float SHAPE_SCALE = 1.5;
const float DETAIL_SCALE = 0.5;

// PERIODS PER SECOND. THE CPU WRAPS TIME AT 2000 SECONDS. EACH OF THESE TIMES 2000 MUST BE A WHOLE
// NUMBER FOR THE WRAP TO BE SEAMLESS
const vec3 WARP_SCROLL = vec3(0.0, -0.0045, 0.002);
const vec3 SHAPE_SCROLL = vec3(0.003, 0.011, -0.004);
const vec3 DETAIL_SCROLL = vec3(-0.012, 0.035, 0.009);

// A PLANE TILTED AWAY FROM EVERY AXIS, SO ALL THREE AXES SWIRL FROM ONE TWO-CHANNEL FETCH
const vec3 WARP_U = vec3(0.70710678, -0.70710678, 0.0);
const vec3 WARP_V = vec3(0.40824829, 0.40824829, -0.81649658);
const float WARP_STRENGTH = 2.7;

// THE WARP CHANGES OVER WARP_SCALE, SO VIEW SAMPLES REUSE IT FOR THIS FAR (BLOCKS) ALONG THE RAY
const float WARP_REUSE = 0.15;

// DISTANCE LOD (BLOCKS): VIEW STEPS GROW PAST STEP_LOD_START. THE DETAIL OCTAVE AND SHADOW MARCH FADE
// OUT BETWEEN DETAIL_LOD_START AND DETAIL_LOD_END, THE SMOOTH FIELD STANDING IN FOR THE SHADOWS
const float BASE_STEP = 0.05;
const float STEP_LOD_START = 8.0;
const float MAX_STEP_SCALE = 4.0;
const float DETAIL_LOD_START = 16.0;
const float DETAIL_LOD_END = 32.0;

const float MIN_TRANSMITTANCE = 0.03;

// WITH THE CAMERA FULLY INSIDE THE GAS, ONLY GAS WITHIN THIS FAR (BLOCKS) IS MARCHED, AND HALF
// IMMERSED, TWICE AS FAR. THE SHADOW MARCH IS SKIPPED TOO: ITS DETAIL DOESN'T SHOW FROM INSIDE
const float IMMERSED_REACH = 8.0;

// THE CPU'S PER-PUFF OPTICAL DEPTHS COVER THE CLOUD BEYOND SHADOW_REACH (BLOCKS)
const float SHADOW_REACH = 0.6;
const float SHADOW_GROWTH = 1.8;

// SUMMED METABALL WEIGHTS TIMES THE SCALE GIVE COVERAGE, AND THE NOISE LEAVES ABOUT SHADOW_FILL OF
// THE SMOOTH FIELD'S GAS. KEEP BOTH IN SYNC WITH FartGasSimulation
const float FIELD_COVERAGE_SCALE = 1.35;
const float SHADOW_FILL = 0.5;

// THE FADE ENDS INSIDE THE SURFACE, WHERE THE DEPTH TEST HIDES IT FROM THE NEAR SIDE ANYWAY
const float WALL_FADE_BEFORE = 0.08;
const float WALL_FADE_INSIDE = 0.04;

// AMBIENT LIGHT ARRIVES FROM EVERY DIRECTION, SO THE GAS ABOVE A SAMPLE BLOCKS IT LESS THAN DIRECT LIGHT
const float AMBIENT_EXTINCTION = 0.4;
const float AMBIENT_FLOOR = 0.4;

// PER BLOCK AT DENSITY 1
const float EXTINCTION = 4.0;
const vec3 BLOCK_LIGHT_TINT = vec3(1.0, 0.78, 0.52);

// RETURNS THE R/G AND B/A CHANNEL PAIRS, EACH INTERPOLATED BETWEEN THIS SLICE AND THE NEXT
vec2 atlas3(sampler2D atlas, vec3 pos, float size, vec2 grid) {
    pos = fract(pos);
    float tile = size + 2.0;
    float sliceCoord = pos.z * size - 0.5;
    float sliceIndex = floor(sliceCoord);
    float slice = mod(sliceIndex + size, size);
    vec2 cell = vec2(mod(slice, grid.x), floor(slice / grid.x));
    vec4 noise = texture(atlas, (cell * tile + 1.0 + pos.xy * size) / (grid * tile));
    return mix(noise.rb, noise.ga, sliceCoord - sliceIndex);
}

float remap(float value, float low, float high) {
    return clamp((value - low) / max(high - low, 1e-4), 0.0, 1.0);
}

// NORMALISED SO ISOTROPIC == 1
float henyeyGreenstein(float cosTheta, float anisotropy) {
    float anisotropySquared = anisotropy * anisotropy;
    return (1.0 - anisotropySquared) / pow(max(1.0 + anisotropySquared - 2.0 * anisotropy * cosTheta, 1e-4), 1.5);
}

float phase(float cosTheta, float eccentricity) {
    return mix(henyeyGreenstein(cosTheta, 0.62 * eccentricity), henyeyGreenstein(cosTheta, -0.22 * eccentricity), 0.28);
}

// MINECRAFT'S LIGHT LEVEL -> BRIGHTNESS CURVE
float brightnessCurve(float level) {
    return level / (4.0 - 3.0 * level);
}

// PUFF INDICES (0-255), FOUR TO A uint TO SAVE REGISTERS. HITS [0, viewHitCount) ARE ON THE VIEW RAY
// HITS [viewHitCount, hitCount) ARE OFF IT BUT WITHIN SHADOW_REACH, SO ONLY SHADOW SAMPLES USE THEM
uint hitWords[MAX_HITS / 4];
int viewHitCount = 0;
int hitCount = 0;

int hitPuff(int hit) {
    return int((hitWords[hit >> 2] >> ((hit & 3) * 8)) & 255u);
}

void addHit(int puffIndex) {
    hitWords[hitCount >> 2] |= uint(puffIndex) << ((hitCount & 3) * 8);
    hitCount++;
}

void setHit(int hit, int puffIndex) {
    int shift = (hit & 3) * 8;
    hitWords[hit >> 2] = (hitWords[hit >> 2] & ~(255u << shift)) | (uint(puffIndex) << shift);
}

// A SAMPLE AT t IS INSIDE A VIEW HIT ONLY WHEN |t - hitMid[hit]| < hitHalf[hit], AND ITS SHADOW SAMPLES
// CAN ONLY REACH THE HIT WHEN |t - hitMid[hit]| < hitReach[hit] (THE PUFF GROWN BY SHADOW_REACH). INSIDE
// A CLOUD EACH SAMPLE IS ONLY IN A FEW OF THE RAY'S PUFFS, SO THE FIELD LOOPS SKIP THE REST UNREAD
float hitMid[MAX_HITS];
float hitHalf[MAX_VIEW_HITS];
float hitReach[MAX_HITS];

void setWindow(int hit, vec3 toCenter, float alongRay, float radius) {
    float offRay = dot(toCenter, toCenter) - alongRay * alongRay;
    float reach = radius + SHADOW_REACH;
    hitMid[hit] = alongRay;
    if (hit < MAX_VIEW_HITS) {
        hitHalf[hit] = sqrt(max(radius * radius - offRay, 0.0));
    }
    hitReach[hit] = sqrt(max(reach * reach - offRay, 0.0));
}

float spanStart[MAX_VIEW_HITS];
float spanEnd[MAX_VIEW_HITS];
int spanCount = 0;

// SHAPE (COVERAGE) AND DENSITY ARE SEPARATE SO DISSIPATING GAS GETS MORE TRANSPARENT. IF THEY WERE ONE
// VALUE, IT WOULD ERODE INTO ISOLATED DOTS
struct Field {
    float coverage;    // 0-1, HOW FAR INSIDE THE GAS THE POINT IS, HOWEVER THIN THE GAS
    float density;     // FIELD-WEIGHTED GAS DENSITY
    float skyLight;
    float blockLight;
    float height;      // 0 AT THE BOTTOM OF ITS PUFFS, 1 AT THE TOP
    float sunDepth;    // OPTICAL DEPTH FROM HERE TOWARD THE LIGHT (DENSITY x BLOCKS)
    float skyDepth;    // ... AND STRAIGHT UP
};

Field puffField(vec3 samplePos, float t) {
    float weightSum = 0.0;
    float densitySum = 0.0;
    vec3 lightSum = vec3(0.0);   // SKY LIGHT, BLOCK LIGHT, HEIGHT
    vec2 depthSum = vec2(0.0);   // THE CENTERS' OPTICAL DEPTHS TOWARD THE LIGHT / SKY
    vec2 offsetSum = vec2(0.0);  // THE POINT'S OFFSETS FROM THE CENTERS TOWARD THE LIGHT / SKY

    for (int hit = 0; hit < MAX_VIEW_HITS; hit++) {
        if (hit >= viewHitCount) {
            break;
        }

        if (abs(t - hitMid[hit]) >= hitHalf[hit]) {
            continue;
        }

        int puffIndex = hitPuff(hit);
        vec4 shape = Puffs[puffIndex].shape;
        vec4 data = Puffs[puffIndex].data;
        vec3 offset = samplePos - shape.xyz;
        float distanceSquared = dot(offset, offset) * data.y;
        if (distanceSquared >= 1.0) {
            continue;
        }

        uvec4 bits = Puffs[puffIndex].bits;
        vec4 lowHalves = vec4(bits & 0xFFFFu);
        vec4 highHalves = vec4(bits >> 16u);
        vec2 light = vec2(lowHalves.x, highHalves.x) / 65535.0;

        // DISTANCE TO THE NEAREST WALL AROUND THIS PUFF (NEGATIVE PAST IT)
        vec3 below = vec3(lowHalves.y, highHalves.y, lowHalves.z) * WALL_STEP - WALL_RANGE;
        vec3 above = vec3(highHalves.z, lowHalves.w, highHalves.w) * WALL_STEP - WALL_RANGE;
        vec3 fromBelow = offset - below;
        vec3 toAbove = above - offset;
        float room = min(min(min(fromBelow.x, fromBelow.y), fromBelow.z), min(min(toAbove.x, toAbove.y), toAbove.z));

        float weight = 1.0 - distanceSquared;
        weight = weight * weight * smoothstep(-WALL_FADE_INSIDE, WALL_FADE_BEFORE, room);
        weightSum += weight;
        densitySum += weight * data.x;
        lightSum += weight * vec3(light, clamp(offset.y / shape.w * 0.5 + 0.5, 0.0, 1.0));
        depthSum += weight * data.zw;
        offsetSum += weight * vec2(dot(offset, LightDirection.xyz), offset.y);
    }

    float inverseWeight = 1.0 / max(weightSum, 1e-4);
    Field field;
    field.coverage = clamp(weightSum * FIELD_COVERAGE_SCALE, 0.0, 1.0);
    field.density = densitySum * inverseWeight;
    field.skyLight = lightSum.x * inverseWeight;
    field.blockLight = lightSum.y * inverseWeight;
    field.height = lightSum.z * inverseWeight;

    // EVERY BLOCK CLOSER TO THE LIGHT (OR SKY) THAN THE CENTERS LEAVES ABOUT A BLOCK OF THE SMOOTH
    // FIELD'S GAS BEHIND
    float smoothDensity = field.coverage * field.density * SHADOW_FILL;
    vec2 depths = max((depthSum - smoothDensity * offsetSum) * inverseWeight, vec2(0.0));
    field.sunDepth = depths.x;
    field.skyDepth = depths.y;
    return field;
}

// samplePos IS WITHIN SHADOW_REACH OF THE VIEW RAY AT viewT
vec2 puffCoverage(vec3 samplePos, float viewT) {
    float densitySum = 0.0;
    float weightSum = 0.0;

    for (int hit = 0; hit < MAX_HITS; hit++) {
        if (hit >= hitCount) {
            break;
        }

        if (abs(viewT - hitMid[hit]) >= hitReach[hit]) {
            continue;
        }

        int puffIndex = hitPuff(hit);
        vec4 data = Puffs[puffIndex].data;
        vec3 offset = samplePos - Puffs[puffIndex].shape.xyz;
        float distanceSquared = dot(offset, offset) * data.y;
        if (distanceSquared >= 1.0) {
            continue;
        }

        float weight = 1.0 - distanceSquared;
        weight = weight * weight;
        densitySum += weight * data.x;
        weightSum += weight;
    }

    return vec2(clamp(weightSum * FIELD_COVERAGE_SCALE, 0.0, 1.0), densitySum / max(weightSum, 1e-4));
}

vec3 warpOffset(vec3 noisePos, float time) {
    vec2 noise = atlas3(WarpSampler, noisePos / WARP_SCALE + WARP_SCROLL * time, WARP_SIZE, WARP_GRID) - 0.5;
    return (noise.x * WARP_U + noise.y * WARP_V) * WARP_STRENGTH;
}

// EVEN FULLY COVERED REGIONS KEEP SOME EROSION SO THE CORE NEVER LOOKS LIKE SMOOTH FOG
float baseShape(vec3 noisePos, vec3 warp, float coverage, float time) {
    float shape = atlas3(ShapeSampler, (noisePos + warp) / SHAPE_SCALE + SHAPE_SCROLL * time, SHAPE_SIZE, SHAPE_GRID).x;
    return remap(shape, 1.0 - coverage * 0.74, 1.0) * coverage;
}

float gasDensity(vec3 noisePos, vec3 warp, float time, vec2 field, float height, float detailAmount) {
    float density = baseShape(noisePos, warp, field.x, time);
    if (density <= 0.0) {
        return 0.0;
    }

    if (detailAmount > 0.0) {
        // BILLOWY ON TOP, WISPY UNDERNEATH, STRONGEST AT THE EDGES
        float detail = atlas3(ShapeSampler, (noisePos + warp * 0.55) / DETAIL_SCALE + DETAIL_SCROLL * time, SHAPE_SIZE, SHAPE_GRID).y;
        detail = mix(1.0 - detail, detail, clamp(height * 1.6, 0.0, 1.0));
        density = mix(density, remap(density, detail * 0.55 * (1.0 - density * 0.45), 1.0), detailAmount);
    }

    return density * field.y * GasColor.w;
}

// REUSES THE VIEW SAMPLE'S WARP: IT CHANGES OVER WARP_SCALE, WELL BEYOND SHADOW_REACH, SO SHADOWS STILL
// FOLLOW THE VISIBLE (WARPED) SHAPES
float shadowDensity(vec3 samplePos, float viewT, vec3 warp, float time) {
    vec2 field = puffCoverage(samplePos, viewT);
    if (field.x <= 0.002) {
        return 0.0;
    }

    return baseShape(samplePos + NoiseOrigin.xyz, warp, field.x, time) * field.y * GasColor.w;
}

vec3 unprojectView(float deviceDepth) {
    return unproject(unprojectBase, unprojectDepth, deviceDepth);
}

// ONE BIT PER RUN WHOSE SCREEN BOUNDS HOLD THIS PIXEL. A RUN IS WHOLE CLOUDS, AND PUFFS CLOSER THAN
// SHADOW_REACH ARE ALWAYS IN THE SAME CLOUD (SEE CLUSTER_GAP), SO NO OTHER RUN CAN ADD GAS OR SHADOW
int runsAtPixel() {
    vec2 ndc = (ProjMat * unprojectBase).xy;
    int runs = 0;
    for (int run = 0; run < MAX_RECTS; run++) {
        vec4 bounds = runBounds[run];
        if (all(greaterThanEqual(ndc, bounds.xy)) && all(lessThanEqual(ndc, bounds.zw))) {
            runs |= 1 << run;
        }
    }

    return runs;
}

int runStart(int run) {
    return run == 0 ? 0 : rectEnd(run - 1);
}

uint viewMask[MAX_PUFFS / 32];

void setViewHit(int puffIndex, bool hit) {
    uint bit = 1u << uint(puffIndex & 31);
    viewMask[puffIndex >> 5] = hit ? viewMask[puffIndex >> 5] | bit : viewMask[puffIndex >> 5] & ~bit;
}

bool isViewHit(int puffIndex) {
    return (viewMask[puffIndex >> 5] & (1u << uint(puffIndex & 31))) != 0u;
}

// gasT IS WHERE TEMPORAL SMOOTHING REPROJECTS THE EMPTY PIXEL FROM
void writeEmpty(float sceneDistance, float gasT) {
    fragColor = vec4(0.0);
    gasDistance = vec2(sceneDistance, gasT);
}

void main() {
    int puffCount = min(Counts.x, MAX_PUFFS);
    for (int word = 0; word < MAX_HITS / 4; word++) {
        hitWords[word] = 0u;
    }
    for (int word = 0; word < MAX_PUFFS / 32; word++) {
        viewMask[word] = 0u;
    }

    int runs = runsAtPixel();

    vec3 rayOrigin;
    vec3 rayDir;
    viewRay(unprojectBase, unprojectDepth, rayOrigin, rayDir);
    mat3 viewToWorld = mat3(ViewToWorld);

    // AT REDUCED RESOLUTION EACH PIXEL READS THE FULL-RESOLUTION DEPTH AT THE CENTER OF THE BLOCK IT COVERS
    int divisor = max(Counts.w, 1);
    ivec2 depthCoord = min(ivec2(gl_FragCoord.xy) * divisor + divisor / 2, textureSize(DepthSampler, 0) - 1);
    float deviceDepth = texelFetch(DepthSampler, depthCoord, 0).r;
    float sceneT = 1e9;
    float sceneDistance = 1e6;
    if (deviceDepth > 0.0) {
        vec3 sceneView = unprojectView(deviceDepth);
        sceneT = max(dot(viewToWorld * sceneView - rayOrigin, rayDir), 0.0);
        sceneDistance = length(sceneView);
    }

    float emptyT = min(sceneT, 1e4);

    // ONCE THE LIST IS FULL, A PUFF THAT ADDS MORE GAS TO THIS RAY (DENSITY x LENGTH OF RAY INSIDE IT)
    // REPLACES THE ONE THAT ADDS THE LEAST. KEEPING THE FIRST HITS INSTEAD WOULD LET A NEW, DENSE FART
    // (UPLOADED FIRST) CROWD AN OLDER CLOUD OFF THE RAY, ERASING IT THERE
    float immersion = AmbientColor.w;
    float gasLimit = immersion > 0.0 ? min(sceneT, IMMERSED_REACH / immersion) : sceneT;

    int weakest = -1;          // -1 = NOT YET FOUND
    float weakestGas = 0.0;
    for (int run = 0; run < MAX_RECTS; run++) {
        if ((runs & (1 << run)) == 0) {
            continue;
        }

        int runEnd = min(rectEnd(run), puffCount);
        for (int puffIndex = runStart(run); puffIndex < runEnd; puffIndex++) {
            vec4 shape = Puffs[puffIndex].shape;
            vec3 toCenter = shape.xyz - rayOrigin;
            float alongRay = dot(toCenter, rayDir);
            float halfChord = alongRay * alongRay - (dot(toCenter, toCenter) - shape.w * shape.w);
            if (halfChord <= 0.0) {
                continue;
            }

            halfChord = sqrt(halfChord);
            float enterT = max(alongRay - halfChord, 0.0);
            float exitT = min(alongRay + halfChord, gasLimit);
            if (exitT <= enterT) {
                continue;
            }

            if (hitCount < MAX_VIEW_HITS) {
                spanStart[hitCount] = enterT;
                spanEnd[hitCount] = exitT;
                setViewHit(puffIndex, true);
                setWindow(hitCount, toCenter, alongRay, shape.w);
                addHit(puffIndex);
                continue;
            }

            if (weakest < 0) {
                weakestGas = 1e30;
                for (int hit = 0; hit < MAX_VIEW_HITS; hit++) {
                    float gas = Puffs[hitPuff(hit)].data.x * (spanEnd[hit] - spanStart[hit]);
                    if (gas < weakestGas) {
                        weakestGas = gas;
                        weakest = hit;
                    }
                }
            }

            if (Puffs[puffIndex].data.x * (exitT - enterT) <= weakestGas) {
                continue;
            }

            setViewHit(hitPuff(weakest), false);
            setViewHit(puffIndex, true);
            spanStart[weakest] = enterT;
            spanEnd[weakest] = exitT;
            setWindow(weakest, toCenter, alongRay, shape.w);
            setHit(weakest, puffIndex);
            weakest = -1;
        }
    }

    viewHitCount = hitCount;
    if (viewHitCount == 0) {
        writeEmpty(sceneDistance, emptyT);
        return;
    }

    for (int hit = 1; hit < MAX_VIEW_HITS; hit++) {
        if (hit >= viewHitCount) {
            break;
        }

        float start = spanStart[hit];
        float end = spanEnd[hit];
        int slot = hit;
        for (; slot > 0; slot--) {
            if (spanStart[slot - 1] <= start) {
                break;
            }

            spanStart[slot] = spanStart[slot - 1];
            spanEnd[slot] = spanEnd[slot - 1];
        }

        spanStart[slot] = start;
        spanEnd[slot] = end;
    }

    spanCount = 1;
    for (int hit = 1; hit < MAX_VIEW_HITS; hit++) {
        if (hit >= viewHitCount) {
            break;
        }

        if (spanStart[hit] <= spanEnd[spanCount - 1]) {
            spanEnd[spanCount - 1] = max(spanEnd[spanCount - 1], spanEnd[hit]);
        } else {
            spanStart[spanCount] = spanStart[hit];
            spanEnd[spanCount] = spanEnd[hit];
            spanCount++;
        }
    }

    float covered = 0.0;
    for (int spanIndex = 0; spanIndex < MAX_VIEW_HITS; spanIndex++) {
        if (spanIndex >= spanCount) {
            break;
        }

        covered += spanEnd[spanIndex] - spanStart[spanIndex];
    }

    float tStart = spanStart[0];
    float tEnd = spanEnd[spanCount - 1];

    vec3 lightDir = LightDirection.xyz;
    float directStrength = LightDirection.w;

    // SHADOW-ONLY PUFFS: WITHOUT THESE, SHADOWS WOULD DEPEND ON WHICH PUFFS HAPPEN TO BE IN VIEW AND
    // CHANGE AS THE CAMERA TURNS
    if (directStrength > 0.0 && immersion < 1.0 && tStart < DETAIL_LOD_END) {
        for (int run = 0; run < MAX_RECTS; run++) {
            if (hitCount >= MAX_HITS) {
                break;
            }

            if ((runs & (1 << run)) == 0) {
                continue;
            }

            int runEnd = min(rectEnd(run), puffCount);
            for (int puffIndex = runStart(run); puffIndex < runEnd; puffIndex++) {
                if (hitCount >= MAX_HITS) {
                    break;
                }

                if (isViewHit(puffIndex)) {
                    continue;
                }

                vec4 shape = Puffs[puffIndex].shape;
                float reach = shape.w + SHADOW_REACH;
                vec3 toCenter = shape.xyz - rayOrigin;
                float alongRay = dot(toCenter, rayDir);
                float halfChord = alongRay * alongRay - (dot(toCenter, toCenter) - reach * reach);
                if (halfChord <= 0.0) {
                    continue;
                }

                halfChord = sqrt(halfChord);
                if (alongRay + halfChord <= tStart || alongRay - halfChord >= tEnd) {
                    continue;
                }

                setWindow(hitCount, toCenter, alongRay, shape.w);
                addHit(puffIndex);
            }
        }
    }

    float jitter = interleavedGradientNoise(gl_FragCoord.xy, Frame.x);
    float stepSize = BASE_STEP * clamp(tStart / STEP_LOD_START, 1.0, MAX_STEP_SCALE);
    int maxSteps = clamp(Counts.y, 12, MAX_VIEW_STEPS);
    int steps = int(clamp(ceil(covered / stepSize), 12.0, float(maxSteps)));
    float stepLength = covered / float(steps);

    float time = NoiseOrigin.w;
    float cosTheta = dot(rayDir, lightDir);
    int shadowSteps = clamp(Counts.z, 1, MAX_SHADOW_STEPS);
    float firstShadowStep = SHADOW_REACH * (SHADOW_GROWTH - 1.0) / (pow(SHADOW_GROWTH, float(shadowSteps)) - 1.0);

    // ONLY DEPENDS ON THE VIEW/LIGHT ANGLE, SO EVALUATED ONCE PER PIXEL INSTEAD OF PER SAMPLE
    float phase0 = phase(cosTheta, 1.0);
    float phase1 = phase(cosTheta, 0.5) * 0.5;
    float phase2 = phase(cosTheta, 0.25) * 0.25;
    float powderMix = 0.35 * (1.0 - cosTheta) * 0.5;

    // SHADOWS CHANGE SLOWLY ALONG THE RAY, SO THE SHADOW MARCH RUNS ON EVERY OTHER LIT SAMPLE
    float cachedOpticalDepth = 0.0;
    int litSamples = 0;

    vec3 warp = vec3(0.0);
    float warpT = -1e9;

    float transmittance = 1.0;
    vec3 radiance = vec3(0.0);
    float weightedDistance = 0.0;
    float weightTotal = 0.0;

    int span = 0;
    float t = tStart + stepLength * jitter;
    for (int viewStep = 0; viewStep < MAX_VIEW_STEPS; viewStep++) {
        if (viewStep >= steps || transmittance < MIN_TRANSMITTANCE) {
            break;
        }

        // CARRY THE REMAINDER INTO THE NEXT SPAN, SO SAMPLES STAY EVENLY SPACED ALONG THE GAS
        for (int skip = 0; skip < MAX_VIEW_HITS; skip++) {
            if (t <= spanEnd[span] || span + 1 >= spanCount) {
                break;
            }

            t = spanStart[span + 1] + (t - spanEnd[span]);
            span++;
        }

        vec3 samplePos = rayOrigin + rayDir * t;
        Field field = puffField(samplePos, t);

        if (field.coverage > 0.002) {
            vec3 noisePos = samplePos + NoiseOrigin.xyz;
            if (t - warpT >= WARP_REUSE) {
                warp = warpOffset(noisePos, time);
                warpT = t;
            }
            float detailAmount = 1.0 - smoothstep(DETAIL_LOD_START, DETAIL_LOD_END, t);
            float density = gasDensity(noisePos, warp, time, vec2(field.coverage, field.density), field.height, detailAmount);

            if (density > 0.001) {
                float extinctionCoefficient = density * EXTINCTION;
                float smoothDensity = field.coverage * field.density * SHADOW_FILL;

                // NO SUN IN CAVES
                float sunVisibility = directStrength * smoothstep(0.35, 0.95, field.skyLight);
                vec3 directLight = vec3(0.0);

                if (sunVisibility > 0.001) {
                    if ((litSamples & 1) == 0) {
                        // FAR AWAY, OR WITH THE CAMERA IN THE GAS, THE SMOOTH FIELD STANDS IN FOR THE MARCH
                        float shadowLod = max(smoothstep(DETAIL_LOD_START, DETAIL_LOD_END, t), immersion);
                        cachedOpticalDepth = smoothDensity * SHADOW_REACH * GasColor.w;
                        if (shadowLod < 1.0) {
                            float marched = 0.0;
                            float lightStep = firstShadowStep;
                            float lightT = lightStep * (0.5 + jitter * 0.5);
                            for (int shadowStep = 0; shadowStep < MAX_SHADOW_STEPS; shadowStep++) {
                                if (shadowStep >= shadowSteps) {
                                    break;
                                }

                                marched += shadowDensity(samplePos + lightDir * lightT, t, warp, time) * lightStep;
                                lightStep *= SHADOW_GROWTH;
                                lightT += lightStep;
                            }

                            cachedOpticalDepth = mix(marched, cachedOpticalDepth, shadowLod);
                        }

                        // THE CPU'S DEPTHS, MINUS THE FIRST SHADOW_REACH THE MARCH ALREADY COVERED
                        cachedOpticalDepth += max(field.sunDepth - smoothDensity * SHADOW_REACH, 0.0) * GasColor.w;
                        cachedOpticalDepth *= EXTINCTION;
                    }

                    litSamples++;
                    float opticalDepth = cachedOpticalDepth;

                    // MULTIPLE-SCATTERING OCTAVES BRIGHTEN THICK GAS LIKE REAL SMOKE. EACH ATTENUATES
                    // LESS, SCATTERS LESS AND IS MORE ISOTROPIC
                    float direct = exp(-opticalDepth) * phase0
                        + exp(-opticalDepth * 0.42) * phase1
                        + exp(-opticalDepth * 0.1764) * phase2;
                    direct *= 1.0 / 1.75;

                    // POWDER TERM: DARKENS THIN EDGES FACING AWAY FROM THE LIGHT
                    float powder = 1.0 - exp(-extinctionCoefficient * 1.6);
                    direct *= mix(1.0, powder * 1.6, powderMix);

                    directLight = LightColor.rgb * direct * sunVisibility;
                }

                float skyOcclusion = exp(-field.skyDepth * GasColor.w * EXTINCTION * AMBIENT_EXTINCTION);
                float ambientOcclusion = mix(AMBIENT_FLOOR, 1.0, skyOcclusion) * mix(0.6, 1.0, exp(-density * 1.5));
                vec3 ambient = AmbientColor.rgb * brightnessCurve(field.skyLight) * ambientOcclusion;
                ambient += BLOCK_LIGHT_TINT * brightnessCurve(field.blockLight) * 0.9 * ambientOcclusion;
                ambient += vec3(0.012, 0.014, 0.010);

                // DENSE CORES ABSORB MORE BLUE: YELLOW-BROWN IN THE MIDDLE, PALE GREEN AT THE EDGES
                vec3 albedo = mix(GasColor.rgb, GasColor.rgb * vec3(0.82, 0.72, 0.46), clamp(density * 0.7, 0.0, 1.0));
                vec3 inScatter = albedo * (directLight + ambient);

                float stepTransmittance = exp(-extinctionCoefficient * stepLength);
                float absorbed = transmittance * (1.0 - stepTransmittance);
                radiance += inScatter * absorbed;
                weightedDistance += t * absorbed;
                weightTotal += absorbed;
                transmittance *= stepTransmittance;
            }
        }

        t += stepLength;
    }

    float alpha = 1.0 - transmittance;
    if (alpha < 0.003) {
        writeEmpty(sceneDistance, emptyT);
        return;
    }

    // VANILLA FOG AT THE OPACITY-WEIGHTED DEPTH OF THE GAS
    float fogDistance = weightedDistance / max(weightTotal, 1e-4);
    vec3 fogPosition = rayOrigin + rayDir * fogDistance;
    float fogValue = total_fog_value(
        fog_spherical_distance(fogPosition),
        fog_cylindrical_distance(fogPosition),
        FogEnvironmentalStart,
        FogEnvironmentalEnd,
        FogRenderDistanceStart,
        FogRenderDistanceEnd
    );
    radiance = mix(radiance, FogColor.rgb * alpha, fogValue * FogColor.a);

    // DITHER TO HIDE 8-BIT BANDING
    radiance += (jitter - 0.5) / 255.0;

    fragColor = vec4(max(radiance, vec3(0.0)), alpha);
    gasDistance = vec2(sceneDistance, fogDistance);
}
