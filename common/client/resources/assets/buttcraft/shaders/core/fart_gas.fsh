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
// LIGHT LEVEL, NEAREST WALLS, GAS BETWEEN IT AND THE LIGHT / SKY). THIS SHADER TURNS THEM
// INTO A CONTINUOUS PARTICIPATING MEDIUM:
//
//   1. INTERSECT THE VIEW RAY WITH THE BOUNDING SPHERE OF EVERY PUFF THAT CAN REACH THIS
//      PIXEL AND MERGE THE HITS INTO DISJOINT SPANS. ALSO COLLECT NEARBY PUFFS THAT CAN
//      ONLY SHADOW THE RAY.
//   2. MARCH ONLY THOSE SPANS (GAPS BETWEEN SEPARATE CLOUDS COST NO STEPS), CLIPPED
//      BY THE SCENE DEPTH BUFFER.
//   3. BUILD DENSITY FROM A SMOOTH METABALL FIELD, FADED OUT AT THE WALLS AROUND EACH
//      PUFF AND ERODED BY TILEABLE 3D PERLIN-WORLEY / WORLEY NOISE, DOMAIN-WARPED FOR
//      SWIRLING WISPS.
//   4. LIGHT EACH SAMPLE WITH A SHORT SHADOW MARCH TOWARD THE SUN/MOON FOR THE NOISE'S
//      DETAIL, THE CPU'S PER-PUFF OPTICAL DEPTHS FOR THE REST OF THE CLOUD (AND FOR THE
//      SKY ABOVE), A DUAL-LOBE HENYEY-GREENSTEIN PHASE FUNCTION, A MULTIPLE-SCATTERING
//      OCTAVE APPROXIMATION, SKY AMBIENT AND BLOCK LIGHT.
//   5. INTEGRATE WITH ENERGY-CONSERVING BEER-LAMBERT STEPS AND APPLY VANILLA
//      FOG. OUTPUT IS PREMULTIPLIED ALPHA.
//
// UNLESS IT BLENDS STRAIGHT ONTO THE SCENE (FART_GAS_DIRECT), IT ALSO WRITES THE DISTANCES
// THE UPSCALE AND TEMPORAL SMOOTHING NEED, AND WRITES EMPTY PIXELS INSTEAD OF DISCARDING.
// =============================================================================

// LOOP BOUNDS: AT LEAST FartGasSettings.MAX_VIEW_STEPS / MAX_SHADOW_STEPS.
#define MAX_VIEW_STEPS 128
#define MAX_SHADOW_STEPS 8

// PUFFS THE VIEW RAY PASSES THROUGH, AND THE TOTAL INCLUDING PUFFS THAT ONLY SHADOW IT.
// MAX_HITS MUST BE A MULTIPLE OF 4 (SEE hitWords).
#define MAX_VIEW_HITS 20
#define MAX_HITS 24

uniform sampler2D WarpSampler;
uniform sampler2D ShapeSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec4 unprojectBase;
layout(location = 1) in vec4 unprojectDepth;
// EACH RUN'S SCREEN BOUNDS (NDC MIN XY, MAX XY), FROM fart_gas.vsh
layout(location = 2) flat in vec4 runBounds[MAX_RECTS];

layout(location = 0) out vec4 fragColor;
#ifndef FART_GAS_DIRECT
// X = VIEW DISTANCE TO THE SCENE THIS PIXEL MARCHED AGAINST (FOR THE UPSCALE),
// Y = DISTANCE ALONG THE RAY TO THE GAS (FOR TEMPORAL SMOOTHING'S REPROJECTION, AND SO THE
// UPSCALE CAN TELL GAS IN FRONT OF BOTH SURFACES FROM GAS A NEARER ONE HIDES).
layout(location = 1) out vec2 gasDistance;
#endif

// -----------------------------------------------------------------------------
// TILEABLE 3D NOISE STORED AS 2D ATLASES OF SLICES, EACH WITH A 1px WRAPPED GUTTER SO
// HARDWARE BILINEAR FILTERING WRAPS CORRECTLY (THE GPU API ONLY HAS 2D AND CUBEMAP
// TEXTURES, SO NO HARDWARE 3D SAMPLING). EVERY TEXEL ALSO HOLDS THE NEXT SLICE'S VALUES,
// SO ONE FETCH GIVES A Z-INTERPOLATED RESULT.
//
// ShapeSampler: 64 SLICES OF 64x64 IN AN 8x8 GRID (528x528), BAKED FROM THE NOISE VOLUME
// (PERLIN-WORLEY, WORLEY FBM LOW / HIGH, PERLIN FBM):
//   R / G = SHAPE  (PERLIN-WORLEY 0.55 + WORLEY LOW 0.3 + WORLEY HIGH 0.15) AT Z / Z + 1
//   B / A = DETAIL (WORLEY LOW 0.6 + WORLEY HIGH 0.4) AT Z / Z + 1
//
// WarpSampler: THE SAME VOLUME'S PERLIN FBM AND WORLEY FBM (LOW), BOX-FILTERED TO 32
// SLICES OF 32x32 IN AN 8x4 GRID (272x136). THE WARP ONLY CHANGES OVER WARP_SCALE, SO IT
// NEEDS NO MORE DETAIL, AND THE SMALL TEXTURE STAYS IN CACHE:
//   R / G = PERLIN FBM AT Z / Z + 1
//   B / A = WORLEY FBM AT Z / Z + 1
// -----------------------------------------------------------------------------
const float SHAPE_SIZE = 64.0;
const vec2 SHAPE_GRID = vec2(8.0, 8.0);
const float WARP_SIZE = 32.0;
const vec2 WARP_GRID = vec2(8.0, 4.0);

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

// THE WARP'S TWO NOISE VALUES PUSH ALONG TWO DIRECTIONS IN A PLANE TILTED AWAY FROM EVERY
// AXIS, SO ALL THREE AXES STILL SWIRL FROM ONE FETCH. THE STRENGTH KEEPS THE OVERALL SIZE OF
// THE EARLIER THREE-CHANNEL WARP (2.2 PER AXIS, NOW SPREAD OVER TWO DIRECTIONS).
const vec3 WARP_U = vec3(0.70710678, -0.70710678, 0.0);
const vec3 WARP_V = vec3(0.40824829, 0.40824829, -0.81649658);
const float WARP_STRENGTH = 2.7;

// THE WARP CHANGES OVER WARP_SCALE, SO VIEW SAMPLES REUSE IT FOR THIS FAR (BLOCKS) ALONG
// THE RAY. NEAR GAS (0.05 STEPS) FETCHES IT ON ABOUT EVERY THIRD SAMPLE.
const float WARP_REUSE = 0.15;

// DISTANCE LOD (BLOCKS): VIEW STEPS GROW PAST STEP_LOD_START, AND THE DETAIL
// OCTAVE FADES OUT BETWEEN DETAIL_LOD_START AND DETAIL_LOD_END. SO DOES THE SHADOW MARCH:
// THE FINE SELF-SHADOWING IT ADDS IS TOO SMALL TO SEE THAT FAR AWAY, SO THE SMOOTH FIELD
// STANDS IN FOR IT AND PAST DETAIL_LOD_END IT IS SKIPPED.
const float BASE_STEP = 0.05;
const float STEP_LOD_START = 8.0;
const float MAX_STEP_SCALE = 4.0;
const float DETAIL_LOD_START = 16.0;
const float DETAIL_LOD_END = 32.0;

// STOP MARCHING ONCE THIS LITTLE OF THE BACKGROUND STILL SHOWS THROUGH.
const float MIN_TRANSMITTANCE = 0.03;

// WITH THE CAMERA INSIDE THE GAS (AmbientColor.w = 1), ONLY GAS WITHIN THIS FAR (BLOCKS) IS
// MARCHED: WHAT IS BEHIND IT SHOWS FAINTLY THROUGH THE GAS IN FRONT, AND THE STEPS ARE WORTH MORE
// UP CLOSE. HALF IMMERSED, IT REACHES TWICE AS FAR, AND SO ON. THE SHADOW MARCH IS SKIPPED TOO:
// LOOKING THROUGH THE GAS RATHER THAN AT ITS LIT SURFACE, ITS FINE SELF-SHADOWING DOESN'T SHOW.
const float IMMERSED_REACH = 8.0;

// THE SHADOW MARCH ONLY REACHES THIS FAR (BLOCKS) TOWARD THE LIGHT, FOR THE NOISE'S FINE
// SELF-SHADOWING; THE CPU'S PER-PUFF OPTICAL DEPTHS COVER THE REST OF THE CLOUD. ITS STEPS
// GROW GEOMETRICALLY, WHATEVER THE STEP COUNT, SO LOWER QUALITY GIVES COARSER SHADOWS
// RATHER THAN SHORTER ONES.
const float SHADOW_REACH = 0.6;
const float SHADOW_GROWTH = 1.8;

// THE METABALL FIELD: SUMMED WEIGHTS TIMES THIS GIVE ITS COVERAGE, AND THE NOISE LEAVES ABOUT
// SHADOW_FILL OF THE SMOOTH FIELD'S GAS. KEEP BOTH IN SYNC WITH FartGasSimulation.
const float FIELD_COVERAGE_SCALE = 1.35;
const float SHADOW_FILL = 0.5;

// GAS FADES OUT AROUND THE WALLS NEXT TO ITS PUFFS: FROM THIS FAR (BLOCKS) BEFORE THE SURFACE
// TO THIS FAR INSIDE IT, WHERE THE DEPTH TEST HIDES IT FROM THE NEAR SIDE ANYWAY.
const float WALL_FADE_BEFORE = 0.08;
const float WALL_FADE_INSIDE = 0.04;

// AMBIENT LIGHT GETS INTO THE GAS MORE EASILY THAN DIRECT LIGHT (IT ARRIVES FROM EVERY
// DIRECTION AND SCATTERS AROUND), SO THE GAS ABOVE A SAMPLE ONLY BLOCKS THIS FRACTION AS MUCH,
// AND NEVER MORE THAN DOWN TO AMBIENT_FLOOR.
const float AMBIENT_EXTINCTION = 0.4;
const float AMBIENT_FLOOR = 0.4;

// OPTICAL PROPERTIES (PER BLOCK AT DENSITY 1).
const float EXTINCTION = 4.0;
const vec3 BLOCK_LIGHT_TINT = vec3(1.0, 0.78, 0.52);

// ONE FETCH FROM A PRE-BLENDED ATLAS OF size^3 TEXELS LAID OUT IN A grid OF SLICES: THE
// R/G AND B/A CHANNEL PAIRS, EACH INTERPOLATED BETWEEN THIS SLICE AND THE NEXT.
vec2 atlas3(sampler2D atlas, vec3 p, float size, vec2 grid) {
    p = fract(p);
    float tile = size + 2.0;
    float z = p.z * size - 0.5;
    float zi = floor(z);
    float slice = mod(zi + size, size);
    vec2 cell = vec2(mod(slice, grid.x), floor(slice / grid.x));
    vec4 n = texture(atlas, (cell * tile + 1.0 + p.xy * size) / (grid * tile));
    return mix(n.rb, n.ga, z - zi);
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

// -----------------------------------------------------------------------------
// PUFF FIELD
// -----------------------------------------------------------------------------

// THE HIT PUFFS' INDICES (0-255), FOUR TO A uint SO THE LIST TAKES A QUARTER OF THE REGISTERS.
// HITS [0, viewHitCount) ARE ON THE VIEW RAY. HITS [viewHitCount, hitCount) ARE OFF IT BUT
// WITHIN SHADOW_REACH, SO THEY ONLY MATTER TO SHADOW SAMPLES.
uint hitWords[MAX_HITS / 4];
int viewHitCount = 0;
int hitCount = 0;

int hitPuff(int k) {
    return int((hitWords[k >> 2] >> ((k & 3) * 8)) & 255u);
}

void addHit(int puff) {
    hitWords[hitCount >> 2] |= uint(puff) << ((hitCount & 3) * 8);
    hitCount++;
}

void setHit(int k, int puff) {
    int shift = (k & 3) * 8;
    hitWords[k >> 2] = (hitWords[k >> 2] & ~(255u << shift)) | (uint(puff) << shift);
}

// WHERE ALONG THE VIEW RAY EACH HIT MATTERS: A SAMPLE AT t IS INSIDE VIEW HIT k ONLY WHEN
// |t - hitMid[k]| < hitHalf[k], AND ITS SHADOW SAMPLES CAN ONLY REACH HIT k WHEN
// |t - hitMid[k]| < hitReach[k] (THE PUFF GROWN BY SHADOW_REACH). INSIDE A CLOUD THE RAY HITS
// MOST OF ITS PUFFS, BUT EACH SAMPLE IS ONLY IN A FEW, SO THE FIELD LOOPS SKIP THE REST WITHOUT
// READING THEM.
float hitMid[MAX_HITS];
float hitHalf[MAX_VIEW_HITS];
float hitReach[MAX_HITS];

// SETS HIT k'S WINDOWS. toCenter / b ARE THE PUFF'S CENTER FROM THE RAY ORIGIN, AND ALONG THE RAY.
void setWindow(int k, vec3 toCenter, float b, float radius) {
    float offRay = dot(toCenter, toCenter) - b * b;
    float reach = radius + SHADOW_REACH;
    hitMid[k] = b;
    if (k < MAX_VIEW_HITS) {
        hitHalf[k] = sqrt(max(radius * radius - offRay, 0.0));
    }
    hitReach[k] = sqrt(max(reach * reach - offRay, 0.0));
}

// THE VIEW RAY'S PUFF INTERVALS, SORTED AND MERGED INTO DISJOINT SPANS.
float spanStart[MAX_VIEW_HITS];
float spanEnd[MAX_VIEW_HITS];
int spanCount = 0;

// THE SMOOTH METABALL FIELD OVER THE VIEW-RAY PUFFS AT ONE POINT, AND ITS LIGHTING, EACH PUFF
// WEIGHTED BY HOW MUCH OF THE POINT IT COVERS. KEEPING SHAPE (COVERAGE) AND DENSITY SEPARATE
// MEANS DISSIPATING GAS GETS MORE TRANSPARENT INSTEAD OF BEING ERODED INTO ISOLATED DOTS.
struct Field {
    float coverage;    // 0-1, HOW FAR INSIDE THE GAS THE POINT IS, HOWEVER THIN THE GAS
    float density;     // FIELD-WEIGHTED GAS DENSITY
    float skyLight;
    float blockLight;
    float height;      // 0 AT THE BOTTOM OF ITS PUFFS, 1 AT THE TOP
    float sunDepth;    // OPTICAL DEPTH FROM HERE TOWARD THE LIGHT (DENSITY x BLOCKS)
    float skyDepth;    // ... AND STRAIGHT UP
};

// p IS THE VIEW RAY AT t.
Field puffField(vec3 p, float t) {
    float weightSum = 0.0;
    float densitySum = 0.0;
    vec3 lightSum = vec3(0.0);   // SKY LIGHT, BLOCK LIGHT, HEIGHT
    vec2 depthSum = vec2(0.0);   // THE CENTERS' OPTICAL DEPTHS TOWARD THE LIGHT / SKY
    vec2 offsetSum = vec2(0.0);  // HOW FAR TOWARD THE LIGHT / SKY THE POINT IS FROM EACH CENTER

    for (int k = 0; k < MAX_VIEW_HITS; k++) {
        if (k >= viewHitCount) {
            break;
        }

        if (abs(t - hitMid[k]) >= hitHalf[k]) {
            continue;
        }

        int i = hitPuff(k);
        vec4 shape = Puffs[i].shape;
        vec4 data = Puffs[i].data;
        vec3 d = p - shape.xyz;
        float d2 = dot(d, d) * data.y;
        if (d2 >= 1.0) {
            continue;
        }

        uvec4 bits = Puffs[i].bits;
        vec4 lo = vec4(bits & 0xFFFFu);
        vec4 hi = vec4(bits >> 16u);
        vec2 light = vec2(lo.x, hi.x) / 65535.0;

        // HOW FAR THE POINT IS FROM THE NEAREST WALL AROUND THIS PUFF (NEGATIVE PAST IT).
        vec3 below = vec3(lo.y, hi.y, lo.z) * WALL_STEP - WALL_RANGE;
        vec3 above = vec3(hi.z, lo.w, hi.w) * WALL_STEP - WALL_RANGE;
        vec3 fromBelow = d - below;
        vec3 toAbove = above - d;
        float room = min(min(min(fromBelow.x, fromBelow.y), fromBelow.z), min(min(toAbove.x, toAbove.y), toAbove.z));

        float w = 1.0 - d2;
        w = w * w * smoothstep(-WALL_FADE_INSIDE, WALL_FADE_BEFORE, room);
        weightSum += w;
        densitySum += w * data.x;
        lightSum += w * vec3(light, clamp(d.y / shape.w * 0.5 + 0.5, 0.0, 1.0));
        depthSum += w * data.zw;
        offsetSum += w * vec2(dot(d, LightDirection.xyz), d.y);
    }

    float inverseWeight = 1.0 / max(weightSum, 1e-4);
    Field field;
    field.coverage = clamp(weightSum * FIELD_COVERAGE_SCALE, 0.0, 1.0);
    field.density = densitySum * inverseWeight;
    field.skyLight = lightSum.x * inverseWeight;
    field.blockLight = lightSum.y * inverseWeight;
    field.height = lightSum.z * inverseWeight;

    // THE CENTERS' DEPTHS, CORRECTED FOR WHERE THE POINT SITS IN ITS PUFFS: EVERY BLOCK CLOSER TO
    // THE LIGHT (OR SKY) LEAVES ABOUT A BLOCK OF THE SMOOTH FIELD'S GAS BEHIND.
    float smoothDensity = field.coverage * field.density * SHADOW_FILL;
    vec2 depths = max((depthSum - smoothDensity * offsetSum) * inverseWeight, vec2(0.0));
    field.sunDepth = depths.x;
    field.skyDepth = depths.y;
    return field;
}

// THE SAME FIELD WITHOUT THE LIGHTING OR WALLS, OVER EVERY PUFF THAT CAN SHADOW THE RAY. p IS
// WITHIN SHADOW_REACH OF THE VIEW RAY AT viewT.
vec2 puffCoverage(vec3 p, float viewT) {
    float densitySum = 0.0;
    float weightSum = 0.0;

    for (int k = 0; k < MAX_HITS; k++) {
        if (k >= hitCount) {
            break;
        }

        if (abs(viewT - hitMid[k]) >= hitReach[k]) {
            continue;
        }

        int i = hitPuff(k);
        vec4 data = Puffs[i].data;
        vec3 d = p - Puffs[i].shape.xyz;
        float d2 = dot(d, d) * data.y;
        if (d2 >= 1.0) {
            continue;
        }

        float w = 1.0 - d2;
        w = w * w;
        densitySum += w * data.x;
        weightSum += w;
    }

    return vec2(clamp(weightSum * FIELD_COVERAGE_SCALE, 0.0, 1.0), densitySum / max(weightSum, 1e-4));
}

vec3 warpOffset(vec3 wp, float time) {
    vec2 n = atlas3(WarpSampler, wp / WARP_SCALE + WARP_SCROLL * time, WARP_SIZE, WARP_GRID) - 0.5;
    return (n.x * WARP_U + n.y * WARP_V) * WARP_STRENGTH;
}

// BASE SHAPE: ERODE THE SMOOTH FIELD WITH LOW-FREQUENCY PERLIN-WORLEY. EVEN FULLY
// COVERED REGIONS KEEP SOME EROSION SO THE CORE NEVER LOOKS LIKE SMOOTH FOG.
float baseShape(vec3 wp, vec3 warp, float coverage, float time) {
    float shape = atlas3(ShapeSampler, (wp + warp) / SHAPE_SCALE + SHAPE_SCROLL * time, SHAPE_SIZE, SHAPE_GRID).x;
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
        float detail = atlas3(ShapeSampler, (wp + warp * 0.55) / DETAIL_SCALE + DETAIL_SCROLL * time, SHAPE_SIZE, SHAPE_GRID).y;
        detail = mix(1.0 - detail, detail, clamp(height * 1.6, 0.0, 1.0));
        density = mix(density, remap(density, detail * 0.55 * (1.0 - density * 0.45), 1.0), detailAmount);
    }

    return density * field.y * GasColor.w;
}

// CHEAPER DENSITY FOR SHADOW SAMPLES: NO DETAIL OCTAVE, AND IT REUSES THE VIEW SAMPLE'S
// DOMAIN WARP INSTEAD OF FETCHING ITS OWN. THE WARP CHANGES OVER WARP_SCALE (4 BLOCKS),
// WELL BEYOND SHADOW_REACH, SO SHADOWS STILL FOLLOW THE VISIBLE (WARPED) SHAPES.
float shadowDensity(vec3 p, float viewT, vec3 warp, float time) {
    vec2 field = puffCoverage(p, viewT);
    if (field.x <= 0.002) {
        return 0.0;
    }

    return baseShape(p + NoiseOrigin.xyz, warp, field.x, time) * field.y * GasColor.w;
}

// -----------------------------------------------------------------------------

vec3 unprojectView(float deviceDepth) {
    return unproject(unprojectBase, unprojectDepth, deviceDepth);
}

// ONE BIT PER RUN OF PUFFS (SEE RectEnds) WHOSE SCREEN BOUNDS HOLD THIS PIXEL. A RUN IS WHOLE
// CLOUDS, AND PUFFS CLOSER THAN SHADOW_REACH ARE ALWAYS IN THE SAME CLOUD (CLUSTER_GAP IN
// FartGasSimulation IS LARGER), SO NO OTHER RUN CAN ADD GAS OR SHADOW HERE.
int runsAtPixel() {
    vec2 ndc = (ProjMat * unprojectBase).xy;
    int runs = 0;
    for (int r = 0; r < MAX_RECTS; r++) {
        vec4 b = runBounds[r];
        if (all(greaterThanEqual(ndc, b.xy)) && all(lessThanEqual(ndc, b.zw))) {
            runs |= 1 << r;
        }
    }

    return runs;
}

int runStart(int r) {
    return r == 0 ? 0 : rectEnd(r - 1);
}

// ONE BIT PER PUFF: WHETHER IT IS ON THE VIEW RAY'S HIT LIST.
uint viewMask[MAX_PUFFS / 32];

void setViewHit(int i, bool hit) {
    uint bit = 1u << uint(i & 31);
    viewMask[i >> 5] = hit ? viewMask[i >> 5] | bit : viewMask[i >> 5] & ~bit;
}

bool isViewHit(int i) {
    return (viewMask[i >> 5] & (1u << uint(i & 31))) != 0u;
}

// A PIXEL WITH NO VISIBLE GAS. gasT IS WHERE TEMPORAL SMOOTHING REPROJECTS IT FROM.
void writeEmpty(float sceneDistance, float gasT) {
    #ifdef FART_GAS_DIRECT
    discard;
    #else
    fragColor = vec4(0.0);
    gasDistance = vec2(sceneDistance, gasT);
    #endif
}

void main() {
    int puffCount = min(Counts.x, MAX_PUFFS);
    for (int w = 0; w < MAX_HITS / 4; w++) {
        hitWords[w] = 0u;
    }
    for (int w = 0; w < MAX_PUFFS / 32; w++) {
        viewMask[w] = 0u;
    }

    int runs = runsAtPixel();

    vec3 ro;
    vec3 rd;
    viewRay(unprojectBase, unprojectDepth, ro, rd);
    mat3 viewToWorld = mat3(ViewToWorld);

    // DISTANCE ALONG THE RAY TO THE OPAQUE SCENE (SKY = 0 = INFINITELY FAR). AT REDUCED
    // RESOLUTION EACH PIXEL READS THE FULL-RESOLUTION DEPTH AT THE CENTER OF THE BLOCK IT
    // COVERS. ITS VIEW DISTANCE IS WRITTEN OUT TOO, FOR THE UPSCALE TO COMPARE AGAINST.
    int divisor = max(Counts.w, 1);
    ivec2 depthCoord = min(ivec2(gl_FragCoord.xy) * divisor + divisor / 2, textureSize(DepthSampler, 0) - 1);
    float deviceDepth = texelFetch(DepthSampler, depthCoord, 0).r;
    float sceneT = 1e9;
    float sceneDistance = 1e6;
    if (deviceDepth > 0.0) {
        vec3 sceneView = unprojectView(deviceDepth);
        sceneT = max(dot(viewToWorld * sceneView - ro, rd), 0.0);
        sceneDistance = length(sceneView);
    }

    // WITHOUT GAS, TEMPORAL SMOOTHING REPROJECTS A PIXEL AS IF IT WERE AT THE SCENE.
    float emptyT = min(sceneT, 1e4);

    // 1. BROAD PHASE: RAY / SPHERE AGAINST THE PUFFS OF THE RUNS UNDER THIS PIXEL. ONCE THE LIST
    // IS FULL, A PUFF THAT ADDS MORE GAS TO THIS RAY (DENSITY x LENGTH OF RAY INSIDE IT)
    // REPLACES THE ONE THAT ADDS THE LEAST. KEEPING THE FIRST HITS INSTEAD WOULD LET A NEW,
    // DENSE FART (UPLOADED FIRST) CROWD AN OLDER CLOUD BEHIND OR IN FRONT OF IT OFF THE RAY,
    // ERASING IT THERE.
    // WITH THE CAMERA IN THE GAS, ONLY THE NEAREST GAS IS MARCHED (SEE IMMERSED_REACH).
    float immersion = AmbientColor.w;
    float gasLimit = immersion > 0.0 ? min(sceneT, IMMERSED_REACH / immersion) : sceneT;

    int weakest = -1;          // THE FULL LIST'S WEAKEST HIT (-1 = NOT YET FOUND)
    float weakestGas = 0.0;
    for (int r = 0; r < MAX_RECTS; r++) {
        if ((runs & (1 << r)) == 0) {
            continue;
        }

        int runEnd = min(rectEnd(r), puffCount);
        for (int i = runStart(r); i < runEnd; i++) {
            vec4 shape = Puffs[i].shape;
            vec3 toCenter = shape.xyz - ro;
            float b = dot(toCenter, rd);
            float h = b * b - (dot(toCenter, toCenter) - shape.w * shape.w);
            if (h <= 0.0) {
                continue;
            }

            h = sqrt(h);
            float t0 = max(b - h, 0.0);
            float t1 = min(b + h, gasLimit);
            if (t1 <= t0) {
                continue;
            }

            if (hitCount < MAX_VIEW_HITS) {
                spanStart[hitCount] = t0;
                spanEnd[hitCount] = t1;
                setViewHit(i, true);
                setWindow(hitCount, toCenter, b, shape.w);
                addHit(i);
                continue;
            }

            if (weakest < 0) {
                weakestGas = 1e30;
                for (int k = 0; k < MAX_VIEW_HITS; k++) {
                    float gas = Puffs[hitPuff(k)].data.x * (spanEnd[k] - spanStart[k]);
                    if (gas < weakestGas) {
                        weakestGas = gas;
                        weakest = k;
                    }
                }
            }

            if (Puffs[i].data.x * (t1 - t0) <= weakestGas) {
                continue;
            }

            setViewHit(hitPuff(weakest), false);
            setViewHit(i, true);
            spanStart[weakest] = t0;
            spanEnd[weakest] = t1;
            setWindow(weakest, toCenter, b, shape.w);
            setHit(weakest, i);
            weakest = -1;
        }
    }

    viewHitCount = hitCount;
    if (viewHitCount == 0) {
        writeEmpty(sceneDistance, emptyT);
        return;
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
    // PUFFS HAPPEN TO BE IN VIEW AND CHANGE AS THE CAMERA TURNS. ONLY NEEDED WHERE THE SHADOW
    // MARCH RUNS (NOT ALL THE WAY INSIDE THE GAS, NOR PAST DETAIL_LOD_END).
    if (directStrength > 0.0 && immersion < 1.0 && tStart < DETAIL_LOD_END) {
        for (int r = 0; r < MAX_RECTS; r++) {
            if (hitCount >= MAX_HITS) {
                break;
            }

            if ((runs & (1 << r)) == 0) {
                continue;
            }

            int runEnd = min(rectEnd(r), puffCount);
            for (int i = runStart(r); i < runEnd; i++) {
                if (hitCount >= MAX_HITS) {
                    break;
                }

                if (isViewHit(i)) {
                    continue;
                }

                vec4 shape = Puffs[i].shape;
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

                setWindow(hitCount, toCenter, b, shape.w);
                addHit(i);
            }
        }
    }

    // 2. MARCH SETUP. THE STEP BUDGET IS SPREAD OVER THE COVERED LENGTH ONLY, NOT THE GAPS.
    // THE JITTER CHANGES EVERY FRAME WHEN TEMPORAL SMOOTHING IS ON (Frame.x IS 0 OTHERWISE).
    float jitter = interleavedGradientNoise(gl_FragCoord.xy, Frame.x);
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

    vec3 warp = vec3(0.0);
    float warpT = -1e9;

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
        Field field = puffField(p, t);

        if (field.coverage > 0.002) {
            vec3 wp = p + NoiseOrigin.xyz;
            if (t - warpT >= WARP_REUSE) {
                warp = warpOffset(wp, time);
                warpT = t;
            }
            float detailAmount = 1.0 - smoothstep(DETAIL_LOD_START, DETAIL_LOD_END, t);
            float density = gasDensity(wp, warp, time, vec2(field.coverage, field.density), field.height, detailAmount);

            if (density > 0.001) {
                float sigmaT = density * EXTINCTION;
                float smoothDensity = field.coverage * field.density * SHADOW_FILL;

                // DIRECT LIGHT IS GATED BY THE PUFF'S SKY ACCESS (NO SUN IN CAVES).
                float sunVisibility = directStrength * smoothstep(0.35, 0.95, field.skyLight);
                vec3 directLight = vec3(0.0);

                // 3. SHADOWS, SKIPPED ENTIRELY WHEN NO DIRECT LIGHT REACHES THIS SAMPLE.
                if (sunVisibility > 0.001) {
                    if ((litSamples & 1) == 0) {
                        // 3A. A SHORT MARCH TOWARD THE LIGHT WITH GEOMETRICALLY GROWING STEPS, FOR
                        // THE SELF-SHADOWING OF THE NOISE'S BILLOWS AND WISPS. FAR AWAY, OR WITH THE
                        // CAMERA IN THE GAS, THE SMOOTH FIELD AT THIS SAMPLE STANDS IN FOR IT (SEE
                        // DETAIL_LOD_END AND IMMERSED_REACH).
                        float shadowLod = max(smoothstep(DETAIL_LOD_START, DETAIL_LOD_END, t), immersion);
                        cachedOpticalDepth = smoothDensity * SHADOW_REACH * GasColor.w;
                        if (shadowLod < 1.0) {
                            float marched = 0.0;
                            float lightStep = firstShadowStep;
                            float lightT = lightStep * (0.5 + jitter * 0.5);
                            for (int l = 0; l < MAX_SHADOW_STEPS; l++) {
                                if (l >= shadowSteps) {
                                    break;
                                }

                                marched += shadowDensity(p + lightDir * lightT, t, warp, time) * lightStep;
                                lightStep *= SHADOW_GROWTH;
                                lightT += lightStep;
                            }

                            cachedOpticalDepth = mix(marched, cachedOpticalDepth, shadowLod);
                        }

                        // 3B. THE REST OF THE WAY OUT OF THE CLOUD FROM THE CPU'S PER-PUFF DEPTHS:
                        // ALL OF IT BUT THE FIRST SHADOW_REACH, WHICH THE MARCH ALREADY COVERED.
                        cachedOpticalDepth += max(field.sunDepth - smoothDensity * SHADOW_REACH, 0.0) * GasColor.w;
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

                // AMBIENT: SKY LIGHT, DIMMED BY HOW MUCH GAS IS ABOVE THIS SAMPLE, + WARM BLOCK LIGHT.
                float skyOcclusion = exp(-field.skyDepth * GasColor.w * EXTINCTION * AMBIENT_EXTINCTION);
                float ambientOcclusion = mix(AMBIENT_FLOOR, 1.0, skyOcclusion) * mix(0.6, 1.0, exp(-density * 1.5));
                vec3 ambient = AmbientColor.rgb * brightnessCurve(field.skyLight) * ambientOcclusion;
                ambient += BLOCK_LIGHT_TINT * brightnessCurve(field.blockLight) * 0.9 * ambientOcclusion;
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
        writeEmpty(sceneDistance, emptyT);
        return;
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
    #ifndef FART_GAS_DIRECT
    gasDistance = vec2(sceneDistance, fogDistance);
    #endif
}
