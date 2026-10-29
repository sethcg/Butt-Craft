#ifndef BUTTCRAFT_FART_GAS_COMMON_GLSL
#define BUTTCRAFT_FART_GAS_COMMON_GLSL

// SHARED BY EVERY FART GAS SHADER. THE BLOCK LAYOUT MUST MATCH FartGasUniforms.java.

// A MULTIPLE OF 32, AT MOST 256 (fart_gas.fsh STORES PUFF INDICES IN 8 BITS) AND 335 (16 KB, THE
// SMALLEST UNIFORM BLOCK OPENGL GUARANTEES). MUST MATCH FartGasSimulation.MAX_PUFFS.
#define MAX_PUFFS 256
// A MULTIPLE OF 4 (RectEnds PACKS FOUR TO AN ivec4). MUST MATCH FartGasSimulation.MAX_RECTS.
#define MAX_RECTS 8

// THE WALL OFFSETS IN Puff.bits: 16-BIT FIXED POINT, FROM -WALL_RANGE IN STEPS OF WALL_STEP
// (BLOCKS). A SIDE WITH NO WALL NEARBY IS AT +-WALL_RANGE, WELL OUTSIDE ANY PUFF. MUST MATCH
// FartGasUniforms.
#define WALL_RANGE 8.0
#define WALL_STEP (1.0 / 4096.0)

// ONE PUFF (48 BYTES, SO 256 OF THEM FIT IN A 16 KB BLOCK).
struct Puff {
    vec4 shape;    // CAMERA-RELATIVE CENTER XYZ, RADIUS
    vec4 data;     // DENSITY, 1 / RADIUS^2, OPTICAL DEPTH TOWARD THE LIGHT, ... AND TOWARD THE SKY
    // TWO 16-BIT VALUES EACH (LOW BITS FIRST): SKY LIGHT AND BLOCK LIGHT (0-1), THEN THE NEAREST
    // WALLS BELOW XYZ AND ABOVE XYZ, FROM THE CENTER (SEE WALL_RANGE)
    uvec4 bits;
};

layout(std140) uniform FartGasInfo {
    mat4 ViewToWorld;      // VIEW SPACE -> CAMERA-RELATIVE WORLD SPACE (ROTATION ONLY)
    mat4 PrevWorldToView;  // LAST FRAME'S CAMERA-RELATIVE WORLD SPACE -> VIEW SPACE (ROTATION ONLY)
    vec4 NoiseOrigin;      // XYZ = CAMERA POSITION WRAPPED TO THE NOISE PERIOD, W = TIME IN SECONDS
    vec4 LightDirection;   // XYZ = NORMALISED WORLD DIRECTION TOWARD SUN/MOON, W = DIRECT LIGHT STRENGTH
    vec4 LightColor;       // RGB = DIRECT LIGHT COLOR
    vec4 AmbientColor;     // RGB = SKY AMBIENT COLOR, W = HOW FAR INSIDE THE GAS THE CAMERA IS (0-1)
    vec4 GasColor;         // RGB = SCATTERING ALBEDO, W = DENSITY MULTIPLIER
    vec4 CameraMotion;     // XYZ = CAMERA POSITION MINUS LAST FRAME'S, W = HISTORY WEIGHT (0 = NO HISTORY)
    ivec4 Counts;          // X = PUFF COUNT, Y = MAX VIEW STEPS, Z = SHADOW STEPS, W = RESOLUTION DIVISOR
    ivec4 Frame;           // X = FRAME NUMBER (VARIES THE JITTER), Y = SCREEN RECTANGLE COUNT
    ivec4 RectEnds[MAX_RECTS / 4]; // WHERE EACH SCREEN RECTANGLE'S RUN OF PUFFS ENDS (EXCLUSIVE); SEE rectEnd
    // ONE RUN PER SCREEN RECTANGLE, DENSEST FIRST IN EACH; NEAR-INVISIBLE PUFFS CULLED ON THE CPU.
    // (OPTICAL DEPTHS FROM THE CENTER THROUGH THE SMOOTH FIELD, DENSITY x BLOCKS, BEFORE EXTINCTION)
    Puff Puffs[MAX_PUFFS];
};

// WHERE A RUN OF PUFFS ENDS (EXCLUSIVE). EACH RUN STARTS WHERE THE ONE BEFORE IT ENDS (RUN 0 AT 0).
int rectEnd(int run) {
    return RectEnds[run >> 2][run & 3];
}

float deviceToNdcDepth(float deviceDepth) {
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    return deviceDepth;
    #else
    return deviceDepth * 2.0 - 1.0;
    #endif
}

// VIEW-SPACE POSITION FOR A DEVICE DEPTH (REVERSED-Z: 1 = NEAR, 0 = FAR), GIVEN THE PIXEL'S
// UNPROJECTION TERMS FROM fart_gas.vsh.
vec3 unproject(vec4 base, vec4 depthColumn, float deviceDepth) {
    vec4 homogeneous = base + deviceToNdcDepth(deviceDepth) * depthColumn;
    return homogeneous.xyz / homogeneous.w;
}

// THE PIXEL'S VIEW RAY IN CAMERA-RELATIVE WORLD SPACE: FROM THE NEAR PLANE THROUGH A FAR POINT.
// BOTH ARE REAL UNPROJECTED POINTS, SO THE RAY STAYS CORRECT WHILE VIEW BOBBING OFFSETS THE EYE.
void viewRay(vec4 base, vec4 depthColumn, out vec3 origin, out vec3 direction) {
    mat3 viewToWorld = mat3(ViewToWorld);
    vec3 nearView = unproject(base, depthColumn, 1.0);
    vec3 farView = unproject(base, depthColumn, 0.001);
    origin = viewToWorld * nearView;
    direction = normalize(viewToWorld * (farView - nearView));
}

// INTERLEAVED GRADIENT NOISE: CHEAP, WELL-DISTRIBUTED PER-PIXEL JITTER. SHIFTING THE PIXEL BY A
// DIFFERENT AMOUNT EACH FRAME GIVES TEMPORAL SMOOTHING A NEW SET OF SAMPLES TO AVERAGE.
float interleavedGradientNoise(vec2 pixel, int frame) {
    pixel += 5.588238 * float(frame);
    return fract(52.9829189 * fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

#endif
