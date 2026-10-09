#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// FULLSCREEN TRIANGLE (NO VERTEX BUFFER) THAT ALSO OUTPUTS WHAT THE FRAGMENT
// SHADER NEEDS TO UNPROJECT ANY DEPTH AT ITS PIXEL:
//
//   inverse(ProjMat) * vec4(ndc.xy, z, 1) = unprojectBase + z * unprojectDepth
//
// THE BASE TERM IS LINEAR IN SCREEN SPACE, SO IT INTERPOLATES EXACTLY. THIS
// STAYS CORRECT WHEN VANILLA FOLDS VIEW BOBBING (A TRANSLATION + ROTATION) INTO
// THE PROJECTION MATRIX, WHERE THE EYE IS NO LONGER AT THE ORIGIN.

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec4 unprojectBase;
layout(location = 2) out vec4 unprojectDepth;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec2 ndc = uv * 2.0 - 1.0;

    gl_Position = vec4(ndc, 0.0, 1.0);
    texCoord = uv;

    mat4 inverseProjection = inverse(ProjMat);
    unprojectBase = inverseProjection * vec4(ndc, 0.0, 1.0);
    unprojectDepth = inverseProjection[2];
}
