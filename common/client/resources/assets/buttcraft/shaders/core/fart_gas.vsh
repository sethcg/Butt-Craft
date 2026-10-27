#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <buttcraft:fart_gas_common.glsl>

// SCREEN-SPACE QUADS (NO VERTEX BUFFER) COVERING ONLY THE PROJECTED BOUNDS OF THE
// PUFFS, SO PIXELS THAT CANNOT SEE ANY GAS NEVER RUN THE RAY MARCHER. IT ALSO
// OUTPUTS WHAT THE FRAGMENT SHADER NEEDS TO UNPROJECT ANY DEPTH AT ITS PIXEL:
//
//   inverse(ProjMat) * vec4(ndc.xy, z, 1) = unprojectBase + z * unprojectDepth
//
// THE BASE TERM IS LINEAR IN SCREEN SPACE, SO IT INTERPOLATES EXACTLY. THIS
// STAYS CORRECT WHEN VANILLA FOLDS VIEW BOBBING (A TRANSLATION + ROTATION) INTO
// THE PROJECTION MATRIX, WHERE THE EYE IS NO LONGER AT THE ORIGIN. THAT IS ALSO WHY
// THE BOUNDS ARE COMPUTED HERE RATHER THAN ON THE CPU: ONLY THE GPU SEES THAT MATRIX.
//
// BY DEFAULT EACH SCREEN RECTANGLE'S RUN OF PUFFS GETS ITS OWN QUAD (6 VERTICES EACH), SO
// SEPARATE CLOUDS DON'T MARCH THE SCREEN BETWEEN THEM. QUADS THAT WOULD OVERLAP A LOT ARE
// MERGED. THAT IS ONLY SAFE WITHOUT BLENDING, SO PASSES THAT BLEND ONTO THE SCENE DEFINE
// FART_GAS_SINGLE_RECT AND DRAW ONE QUAD AROUND EVERY PUFF.

layout(location = 0) out vec4 unprojectBase;
layout(location = 1) out vec4 unprojectDepth;

const vec2 CORNERS[6] = vec2[](
    vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0),
    vec2(0.0, 1.0), vec2(1.0, 0.0), vec2(1.0, 1.0)
);

// A BOX CROSSING THE EYE PLANE IS CLIPPED HERE (CLIP-SPACE W) BEFORE IT IS PROJECTED.
const float NEAR_W = 0.05;

const vec4 EMPTY_RECT = vec4(1.0, 1.0, -1.0, -1.0);

vec4 unionRect(vec4 a, vec4 b) {
    return vec4(min(a.xy, b.xy), max(a.zw, b.zw));
}

float rectArea(vec4 r) {
    vec2 size = max(r.zw - r.xy, vec2(0.0));
    return size.x * size.y;
}

// EXACT NDC BOUNDS OF A SPHERE (VIEW-SPACE CENTER c, RADIUS r) THAT DOESN'T CROSS THE EYE PLANE.
// IN HOMOGENEOUS SCREEN SPACE THE SPHERE'S OUTLINE IS THE CONIC WITH DUAL C = M Q M^T, WHERE M
// IS PROJMAT'S X / Y / W ROWS AND Q THE SPHERE'S DUAL QUADRIC. A VERTICAL LINE x = X TOUCHES IT
// WHERE C00 - 2 X C02 + X^2 C22 = 0 (AND THE SAME FOR y). WORKS FOR ANY PROJMAT, BOBBING
// INCLUDED. C22 < 0 EXACTLY WHEN THE SPHERE IS CLEAR OF THE EYE PLANE.
vec4 sphereRect(vec3 c, float r, vec4 k, float c22) {
    vec3 mx = vec3(ProjMat[0][0], ProjMat[1][0], ProjMat[2][0]);
    vec3 my = vec3(ProjMat[0][1], ProjMat[1][1], ProjMat[2][1]);
    vec3 mw = vec3(ProjMat[0][3], ProjMat[1][3], ProjMat[2][3]);
    float r2 = r * r;
    float c00 = r2 * dot(mx, mx) - k.x * k.x;
    float c02 = r2 * dot(mx, mw) - k.x * k.w;
    float c11 = r2 * dot(my, my) - k.y * k.y;
    float c12 = r2 * dot(my, mw) - k.y * k.w;
    vec2 root = sqrt(max(vec2(c02 * c02 - c00 * c22, c12 * c12 - c11 * c22), vec2(0.0)));
    vec2 a = (vec2(c02, c12) - root) / c22;
    vec2 b = (vec2(c02, c12) + root) / c22;
    return vec4(min(a, b), max(a, b));
}

// CONSERVATIVE NDC BOUNDS OF A SPHERE THAT CROSSES THE EYE PLANE: ITS VIEW-SPACE BOX, CLIPPED
// AT w = NEAR_W. THE CLIPPED BOX'S CORNERS ARE THE BOX CORNERS IN FRONT PLUS WHERE ITS EDGES
// CROSS THE PLANE, SO GAS OFF TO ONE SIDE ONLY COVERS THAT SIDE OF THE SCREEN.
vec4 clippedBoxRect(vec3 c, float r) {
    vec4 clip[8];
    for (int i = 0; i < 8; i++) {
        vec3 corner = vec3(i & 1, (i >> 1) & 1, (i >> 2) & 1) * 2.0 - 1.0;
        clip[i] = ProjMat * vec4(c + corner * r, 1.0);
    }

    vec4 rect = EMPTY_RECT;
    for (int i = 0; i < 8; i++) {
        if (clip[i].w > NEAR_W) {
            vec2 ndc = clip[i].xy / clip[i].w;
            rect = unionRect(rect, vec4(ndc, ndc));
        }

        // THE THREE EDGES FROM THIS CORNER TOWARD ITS HIGHER NEIGHBOURS (EACH EDGE ONCE).
        for (int axis = 0; axis < 3; axis++) {
            int j = i | (1 << axis);
            if (j == i || (clip[i].w > NEAR_W) == (clip[j].w > NEAR_W)) {
                continue;
            }

            vec4 cut = mix(clip[i], clip[j], (NEAR_W - clip[i].w) / (clip[j].w - clip[i].w));
            vec2 ndc = cut.xy / NEAR_W;
            rect = unionRect(rect, vec4(ndc, ndc));
        }
    }

    return rect;
}

// NDC BOUNDS OF PUFFS [first, end).
vec4 puffRect(int first, int end, mat3 worldToView) {
    vec4 rect = EMPTY_RECT;
    for (int i = 0; i < MAX_PUFFS; i++) {
        if (i >= end) {
            break;
        }

        if (i < first) {
            continue;
        }

        vec4 shape = Puffs[i * 4];
        vec3 center = worldToView * shape.xyz;
        vec4 k = ProjMat * vec4(center, 1.0);
        vec3 mw = vec3(ProjMat[0][3], ProjMat[1][3], ProjMat[2][3]);
        float c22 = shape.w * shape.w * dot(mw, mw) - k.w * k.w;
        if (c22 < 0.0) {
            // CLEAR OF THE EYE PLANE: SKIP IT IF IT IS ENTIRELY BEHIND THE CAMERA.
            if (k.w > 0.0) {
                rect = unionRect(rect, sphereRect(center, shape.w, k, c22));
            }
        } else {
            rect = unionRect(rect, clippedBoxRect(center, shape.w));
        }
    }

    // ONE PIXEL-ISH OF SLACK FOR SAMPLE COVERAGE AT THE EDGES.
    return clamp(vec4(rect.xy - 0.01, rect.zw + 0.01), -1.0, 1.0);
}

void main() {
    mat3 worldToView = transpose(mat3(ViewToWorld));
    int puffCount = min(Counts.x, MAX_PUFFS);

    #ifdef FART_GAS_SINGLE_RECT
    vec4 bounds = puffRect(0, puffCount, worldToView);
    #else
    // EVERY VERTEX WORKS OUT ALL THE RECTANGLES (CHEAP: EACH PUFF IS PROJECTED ONCE) AND MERGES
    // THEM THE SAME WAY, SO THE QUADS AGREE. TWO RECTANGLES MERGE WHEN THEIR UNION IS NO BIGGER
    // THAN BOTH OF THEM: MARCHING THE OVERLAP TWICE WOULD COST MORE THAN THE UNION.
    int rectCount = clamp(Frame.y, 1, MAX_RECTS);
    vec4 rects[MAX_RECTS];
    for (int r = 0; r < MAX_RECTS; r++) {
        int first = r == 0 ? 0 : RectEnds[r - 1];
        rects[r] = r < rectCount ? puffRect(first, min(RectEnds[r], puffCount), worldToView) : EMPTY_RECT;
    }

    // A MERGED RECTANGLE MAY NOW OVERLAP ONES IT WAS ALREADY COMPARED WITH, SO REPEAT UNTIL
    // NOTHING MERGES. EACH MERGE EMPTIES A RECTANGLE, SO THAT TAKES AT MOST MAX_RECTS PASSES.
    for (int pass = 0; pass < MAX_RECTS; pass++) {
        bool merged = false;
        for (int a = 0; a < MAX_RECTS; a++) {
            for (int b = a + 1; b < MAX_RECTS; b++) {
                vec4 both = unionRect(rects[a], rects[b]);
                if (rectArea(rects[b]) > 0.0 && rectArea(both) <= rectArea(rects[a]) + rectArea(rects[b])) {
                    rects[a] = both;
                    rects[b] = EMPTY_RECT;
                    merged = true;
                }
            }
        }

        if (!merged) {
            break;
        }
    }

    vec4 bounds = rects[min(gl_VertexIndex / 6, MAX_RECTS - 1)];
    #endif

    vec2 ndc = mix(bounds.xy, bounds.zw, CORNERS[gl_VertexIndex % 6]);

    // AN EMPTY RECTANGLE (LO > HI) GIVES A ZERO-AREA QUAD, SO NOTHING IS RASTERIZED.
    if (bounds.x >= bounds.z || bounds.y >= bounds.w) {
        ndc = vec2(-2.0);
    }

    gl_Position = vec4(ndc, 0.0, 1.0);

    mat4 inverseProjection = inverse(ProjMat);
    unprojectBase = inverseProjection * vec4(ndc, 0.0, 1.0);
    unprojectDepth = inverseProjection[2];
}
