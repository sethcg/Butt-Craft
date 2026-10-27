#ifndef BUTTCRAFT_FART_GAS_COMMON_GLSL
#define BUTTCRAFT_FART_GAS_COMMON_GLSL

// SHARED BY EVERY FART GAS SHADER. THE BLOCK LAYOUT MUST MATCH FartGasUniforms.java.

#define MAX_PUFFS 64
#define MAX_RECTS 4

layout(std140) uniform FartGasInfo {
    mat4 ViewToWorld;      // VIEW SPACE -> CAMERA-RELATIVE WORLD SPACE (ROTATION ONLY)
    mat4 PrevWorldToView;  // LAST FRAME'S CAMERA-RELATIVE WORLD SPACE -> VIEW SPACE (ROTATION ONLY)
    vec4 NoiseOrigin;      // XYZ = CAMERA POSITION WRAPPED TO THE NOISE PERIOD, W = TIME IN SECONDS
    vec4 LightDirection;   // XYZ = NORMALISED WORLD DIRECTION TOWARD SUN/MOON, W = DIRECT LIGHT STRENGTH
    vec4 LightColor;       // RGB = DIRECT LIGHT COLOR
    vec4 AmbientColor;     // RGB = SKY AMBIENT COLOR
    vec4 GasColor;         // RGB = SCATTERING ALBEDO, W = DENSITY MULTIPLIER
    vec4 CameraMotion;     // XYZ = CAMERA POSITION MINUS LAST FRAME'S, W = HISTORY WEIGHT (0 = NO HISTORY)
    ivec4 Counts;          // X = PUFF COUNT, Y = MAX VIEW STEPS, Z = SHADOW STEPS, W = RESOLUTION DIVISOR
    ivec4 Frame;           // X = FRAME NUMBER (VARIES THE JITTER), Y = SCREEN RECTANGLE COUNT
    ivec4 RectEnds;        // WHERE EACH SCREEN RECTANGLE'S RUN OF PUFFS ENDS (EXCLUSIVE)
    vec4 Puffs[MAX_PUFFS * 4];
    // ONE RUN PER SCREEN RECTANGLE, DENSEST FIRST IN EACH; NEAR-INVISIBLE PUFFS CULLED ON THE CPU.
    // PUFFS[4I + 0] = CAMERA-RELATIVE CENTER XYZ, RADIUS
    // PUFFS[4I + 1] = DENSITY, SKY LIGHT (0-1), BLOCK LIGHT (0-1), 1 / RADIUS^2
    // PUFFS[4I + 2] = CAMERA-RELATIVE NEAREST WALLS BELOW XYZ, OPTICAL DEPTH TOWARD THE LIGHT
    // PUFFS[4I + 3] = CAMERA-RELATIVE NEAREST WALLS ABOVE XYZ, OPTICAL DEPTH TOWARD THE SKY
    // (OPTICAL DEPTHS FROM THE CENTER THROUGH THE SMOOTH FIELD, DENSITY x BLOCKS, BEFORE EXTINCTION)
};

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
    vec4 h = base + deviceToNdcDepth(deviceDepth) * depthColumn;
    return h.xyz / h.w;
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
