#ifndef BUTTCRAFT_FART_GAS_COMMON_GLSL
#define BUTTCRAFT_FART_GAS_COMMON_GLSL

// SHARED BY EVERY FART GAS SHADER. THE BLOCK LAYOUT MUST MATCH FartGasUniforms.java.

#define MAX_PUFFS 64

layout(std140) uniform FartGasInfo {
    mat4 ViewToWorld;      // VIEW SPACE -> CAMERA-RELATIVE WORLD SPACE (ROTATION ONLY)
    vec4 NoiseOrigin;      // XYZ = CAMERA POSITION WRAPPED TO THE NOISE PERIOD, W = TIME IN SECONDS
    vec4 LightDirection;   // XYZ = NORMALISED WORLD DIRECTION TOWARD SUN/MOON, W = DIRECT LIGHT STRENGTH
    vec4 LightColor;       // RGB = DIRECT LIGHT COLOR
    vec4 AmbientColor;     // RGB = SKY AMBIENT COLOR
    vec4 GasColor;         // RGB = SCATTERING ALBEDO, W = DENSITY MULTIPLIER
    ivec4 Counts;          // X = PUFF COUNT, Y = MAX VIEW STEPS, Z = SHADOW STEPS, W = RESOLUTION DIVISOR
    vec4 Puffs[MAX_PUFFS * 2];
    // DENSEST FIRST, NEAR-INVISIBLE PUFFS ALREADY CULLED ON THE CPU.
    // PUFFS[2I + 0] = CAMERA-RELATIVE CENTER XYZ, RADIUS
    // PUFFS[2I + 1] = DENSITY, SKY LIGHT (0-1), BLOCK LIGHT (0-1), 1 / RADIUS^2
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

#endif
