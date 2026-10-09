#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// FULLSCREEN TRIANGLE (NO VERTEX BUFFER) THAT ALSO OUTPUTS THE UN-DIVIDED
// VIEW-SPACE POSITION OF EACH CORNER. HOMOGENEOUS COORDINATES INTERPOLATE
// LINEARLY IN SCREEN SPACE, SO THE FRAGMENT SHADER CAN RECOVER AN EXACT
// PER-PIXEL VIEW RAY WITHOUT INVERTING THE PROJECTION PER FRAGMENT.

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec4 viewRayH;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec2 ndc = uv * 2.0 - 1.0;

    gl_Position = vec4(ndc, 0.0, 1.0);
    texCoord = uv;

    // 0.5 IS INSIDE THE FRUSTUM FOR BOTH [-1, 1] AND [0, 1] DEPTH RANGES.
    viewRayH = inverse(ProjMat) * vec4(ndc, 0.5, 1.0);
}
