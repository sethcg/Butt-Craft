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
//
// EITHER WAY IT ALSO OUTPUTS EACH RUN'S OWN BOUNDS (BEFORE ANY MERGING). fart_gas.fsh ONLY
// TESTS THE PUFFS OF THE RUNS WHOSE BOUNDS HOLD ITS PIXEL, SO A PIXEL IN A MERGED QUAD, OR
// WHERE TWO QUADS OVERLAP, STILL SEES EVERY RUN THAT CAN REACH IT AND NO OTHERS.

layout(location = 0) out vec4 unprojectBase;
layout(location = 1) out vec4 unprojectDepth;
// EVERY VERTEX WORKS THESE OUT THE SAME, SO flat LOSES NOTHING. EMPTY RUNS ARE EMPTY_RECT.
layout(location = 2) flat out vec4 runBounds[MAX_RECTS];

const vec2 CORNERS[6] = vec2[](
    vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.0, 1.0),
    vec2(0.0, 1.0), vec2(1.0, 0.0), vec2(1.0, 1.0)
);

// A BOX CROSSING THE EYE PLANE IS CLIPPED HERE (CLIP-SPACE W) BEFORE IT IS PROJECTED.
const float NEAR_W = 0.05;

const vec4 EMPTY_RECT = vec4(1.0, 1.0, -1.0, -1.0);

vec4 unionRect(vec4 rectA, vec4 rectB) {
    return vec4(min(rectA.xy, rectB.xy), max(rectA.zw, rectB.zw));
}

float rectArea(vec4 rect) {
    vec2 size = max(rect.zw - rect.xy, vec2(0.0));
    return size.x * size.y;
}

// EXACT NDC BOUNDS OF A SPHERE (VIEW-SPACE center, radius) THAT DOESN'T CROSS THE EYE PLANE.
// IN HOMOGENEOUS SCREEN SPACE THE SPHERE'S OUTLINE IS THE CONIC WITH DUAL C = M Q M^T, WHERE M
// IS PROJMAT'S X / Y / W ROWS AND Q THE SPHERE'S DUAL QUADRIC. A VERTICAL LINE x = X TOUCHES IT
// WHERE C00 - 2 X C02 + X^2 C22 = 0 (AND THE SAME FOR y). WORKS FOR ANY PROJMAT, BOBBING
// INCLUDED. C22 < 0 EXACTLY WHEN THE SPHERE IS CLEAR OF THE EYE PLANE.
vec4 sphereRect(vec3 center, float radius, vec4 clipCenter, float conic22) {
    vec3 rowX = vec3(ProjMat[0][0], ProjMat[1][0], ProjMat[2][0]);
    vec3 rowY = vec3(ProjMat[0][1], ProjMat[1][1], ProjMat[2][1]);
    vec3 rowW = vec3(ProjMat[0][3], ProjMat[1][3], ProjMat[2][3]);
    float radiusSquared = radius * radius;
    float conic00 = radiusSquared * dot(rowX, rowX) - clipCenter.x * clipCenter.x;
    float conic02 = radiusSquared * dot(rowX, rowW) - clipCenter.x * clipCenter.w;
    float conic11 = radiusSquared * dot(rowY, rowY) - clipCenter.y * clipCenter.y;
    float conic12 = radiusSquared * dot(rowY, rowW) - clipCenter.y * clipCenter.w;
    vec2 root = sqrt(max(vec2(conic02 * conic02 - conic00 * conic22, conic12 * conic12 - conic11 * conic22), vec2(0.0)));
    vec2 edgeA = (vec2(conic02, conic12) - root) / conic22;
    vec2 edgeB = (vec2(conic02, conic12) + root) / conic22;
    return vec4(min(edgeA, edgeB), max(edgeA, edgeB));
}

// CONSERVATIVE NDC BOUNDS OF A SPHERE THAT CROSSES THE EYE PLANE: ITS VIEW-SPACE BOX, CLIPPED
// AT w = NEAR_W. THE CLIPPED BOX'S CORNERS ARE THE BOX CORNERS IN FRONT PLUS WHERE ITS EDGES
// CROSS THE PLANE, SO GAS OFF TO ONE SIDE ONLY COVERS THAT SIDE OF THE SCREEN.
vec4 clippedBoxRect(vec3 center, float radius) {
    vec4 clip[8];
    for (int cornerIndex = 0; cornerIndex < 8; cornerIndex++) {
        vec3 corner = vec3(cornerIndex & 1, (cornerIndex >> 1) & 1, (cornerIndex >> 2) & 1) * 2.0 - 1.0;
        clip[cornerIndex] = ProjMat * vec4(center + corner * radius, 1.0);
    }

    vec4 rect = EMPTY_RECT;
    for (int cornerIndex = 0; cornerIndex < 8; cornerIndex++) {
        if (clip[cornerIndex].w > NEAR_W) {
            vec2 ndc = clip[cornerIndex].xy / clip[cornerIndex].w;
            rect = unionRect(rect, vec4(ndc, ndc));
        }

        // THE THREE EDGES FROM THIS CORNER TOWARD ITS HIGHER NEIGHBOURS (EACH EDGE ONCE).
        for (int axis = 0; axis < 3; axis++) {
            int neighbourIndex = cornerIndex | (1 << axis);
            if (neighbourIndex == cornerIndex || (clip[cornerIndex].w > NEAR_W) == (clip[neighbourIndex].w > NEAR_W)) {
                continue;
            }

            vec4 cut = mix(clip[cornerIndex], clip[neighbourIndex], (NEAR_W - clip[cornerIndex].w) / (clip[neighbourIndex].w - clip[cornerIndex].w));
            vec2 ndc = cut.xy / NEAR_W;
            rect = unionRect(rect, vec4(ndc, ndc));
        }
    }

    return rect;
}

// NDC BOUNDS OF PUFFS [first, end).
vec4 puffRect(int first, int end, mat3 worldToView) {
    vec4 rect = EMPTY_RECT;
    for (int runOffset = 0; runOffset < MAX_PUFFS; runOffset++) {
        int puffIndex = first + runOffset;
        if (puffIndex >= end) {
            break;
        }

        vec4 shape = Puffs[puffIndex].shape;
        vec3 center = worldToView * shape.xyz;
        vec4 clipCenter = ProjMat * vec4(center, 1.0);
        vec3 rowW = vec3(ProjMat[0][3], ProjMat[1][3], ProjMat[2][3]);
        float conic22 = shape.w * shape.w * dot(rowW, rowW) - clipCenter.w * clipCenter.w;
        if (conic22 < 0.0) {
            // CLEAR OF THE EYE PLANE: SKIP IT IF IT IS ENTIRELY BEHIND THE CAMERA.
            if (clipCenter.w > 0.0) {
                rect = unionRect(rect, sphereRect(center, shape.w, clipCenter, conic22));
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

    // EVERY VERTEX WORKS OUT ALL THE RECTANGLES (CHEAP: EACH PUFF IS PROJECTED ONCE) AND MERGES
    // THEM THE SAME WAY, SO THE QUADS AGREE.
    int rectCount = clamp(Frame.y, 1, MAX_RECTS);
    vec4 rects[MAX_RECTS];
    for (int run = 0; run < MAX_RECTS; run++) {
        int first = run == 0 ? 0 : rectEnd(run - 1);
        rects[run] = run < rectCount ? puffRect(first, min(rectEnd(run), puffCount), worldToView) : EMPTY_RECT;
        runBounds[run] = rects[run];
    }

    #ifdef FART_GAS_SINGLE_RECT
    vec4 bounds = EMPTY_RECT;
    for (int run = 0; run < MAX_RECTS; run++) {
        bounds = unionRect(bounds, rects[run]);
    }
    #else
    // TWO RECTANGLES MERGE WHEN THEIR UNION IS NO BIGGER THAN BOTH OF THEM: MARCHING THE
    // OVERLAP TWICE WOULD COST MORE THAN THE UNION. A MERGED RECTANGLE MAY NOW OVERLAP ONES
    // IT WAS ALREADY COMPARED WITH, SO REPEAT UNTIL NOTHING MERGES. EACH MERGE EMPTIES A
    // RECTANGLE, SO THAT TAKES AT MOST MAX_RECTS PASSES.
    for (int pass = 0; pass < MAX_RECTS; pass++) {
        bool merged = false;
        for (int rectA = 0; rectA < MAX_RECTS; rectA++) {
            for (int rectB = rectA + 1; rectB < MAX_RECTS; rectB++) {
                vec4 both = unionRect(rects[rectA], rects[rectB]);
                if (rectArea(rects[rectB]) > 0.0 && rectArea(both) <= rectArea(rects[rectA]) + rectArea(rects[rectB])) {
                    rects[rectA] = both;
                    rects[rectB] = EMPTY_RECT;
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
