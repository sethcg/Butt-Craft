#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <buttcraft:fart_gas_common.glsl>

// SCREEN-SPACE QUAD (NO VERTEX BUFFER) COVERING ONLY THE PROJECTED BOUNDS OF THE
// PUFFS, SO PIXELS THAT CANNOT SEE ANY GAS NEVER RUN THE RAY MARCHER. IT ALSO
// OUTPUTS WHAT THE FRAGMENT SHADER NEEDS TO UNPROJECT ANY DEPTH AT ITS PIXEL:
//
//   inverse(ProjMat) * vec4(ndc.xy, z, 1) = unprojectBase + z * unprojectDepth
//
// THE BASE TERM IS LINEAR IN SCREEN SPACE, SO IT INTERPOLATES EXACTLY. THIS
// STAYS CORRECT WHEN VANILLA FOLDS VIEW BOBBING (A TRANSLATION + ROTATION) INTO
// THE PROJECTION MATRIX, WHERE THE EYE IS NO LONGER AT THE ORIGIN. THAT IS ALSO WHY
// THE BOUNDS ARE COMPUTED HERE RATHER THAN ON THE CPU: ONLY THE GPU SEES THAT MATRIX.

layout(location = 0) out vec4 unprojectBase;
layout(location = 1) out vec4 unprojectDepth;

const vec2 CORNERS[6] = vec2[](
    vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0),
    vec2(0.0, 1.0), vec2(1.0, 0.0), vec2(1.0, 1.0)
);

// CONSERVATIVE NDC RECTANGLE OF EVERY PUFF: PROJECT THE CORNERS OF EACH SPHERE'S
// VIEW-SPACE BOX. A BOX THAT STRADDLES THE EYE PLANE FALLS BACK TO FULLSCREEN.
vec4 puffBounds() {
    mat3 worldToView = transpose(mat3(ViewToWorld));
    vec2 lo = vec2(1.0);
    vec2 hi = vec2(-1.0);
    int puffCount = min(Counts.x, MAX_PUFFS);

    for (int i = 0; i < MAX_PUFFS; i++) {
        if (i >= puffCount) {
            break;
        }

        vec4 shape = Puffs[i * 2];
        vec3 center = worldToView * shape.xyz;
        int behind = 0;
        vec2 puffLo = vec2(1e9);
        vec2 puffHi = vec2(-1e9);
        for (int c = 0; c < 8; c++) {
            vec3 corner = vec3(c & 1, (c >> 1) & 1, (c >> 2) & 1) * 2.0 - 1.0;
            vec4 clip = ProjMat * vec4(center + corner * shape.w, 1.0);
            if (clip.w <= 0.05) {
                behind++;
                continue;
            }

            vec2 ndc = clip.xy / clip.w;
            puffLo = min(puffLo, ndc);
            puffHi = max(puffHi, ndc);
        }

        if (behind == 8) {
            continue;
        }

        if (behind > 0) {
            return vec4(-1.0, -1.0, 1.0, 1.0);
        }

        lo = min(lo, puffLo);
        hi = max(hi, puffHi);
    }

    // ONE PIXEL-ISH OF SLACK FOR SAMPLE COVERAGE AT THE EDGES.
    return clamp(vec4(lo - 0.01, hi + 0.01), -1.0, 1.0);
}

void main() {
    vec4 bounds = puffBounds();
    vec2 ndc = mix(bounds.xy, bounds.zw, CORNERS[gl_VertexIndex]);

    // AN EMPTY RECTANGLE (LO > HI) GIVES A ZERO-AREA QUAD, SO NOTHING IS RASTERIZED.
    if (bounds.x >= bounds.z || bounds.y >= bounds.w) {
        ndc = vec2(-2.0);
    }

    gl_Position = vec4(ndc, 0.0, 1.0);

    mat4 inverseProjection = inverse(ProjMat);
    unprojectBase = inverseProjection * vec4(ndc, 0.0, 1.0);
    unprojectDepth = inverseProjection[2];
}
