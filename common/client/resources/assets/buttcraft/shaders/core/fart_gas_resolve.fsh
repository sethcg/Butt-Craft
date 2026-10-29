#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <buttcraft:fart_gas_common.glsl>

// =============================================================================
// BUTTCRAFT VOLUMETRIC FART GAS: TEMPORAL SMOOTHING
//
// THE MARCHER JITTERS ITS SAMPLES DIFFERENTLY EVERY FRAME. THIS PASS (AT THE MARCH'S
// RESOLUTION) BLENDS ITS RESULT WITH LAST FRAME'S, SO OVER A FEW FRAMES EACH PIXEL
// AVERAGES MANY MORE SAMPLES THAN ONE MARCH TAKES:
//
//   1. FOLLOW THIS PIXEL'S RAY TO THE GAS (THE MARCHER WROTE HOW FAR ALONG IT THAT IS) AND
//      FIND WHERE THAT POINT WAS ON SCREEN LAST FRAME, USING LAST FRAME'S CAMERA.
//   2. READ LAST FRAME'S RESULT THERE AND CLAMP IT TO THE RANGE OF THIS FRAME'S 3x3
//      NEIGHBOURHOOD, SO GAS THAT MOVED OR CHANGED DOESN'T LEAVE A TRAIL.
//   3. BLEND (CameraMotion.w OF THE HISTORY; 0 WHEN THERE IS NONE).
// =============================================================================

uniform sampler2D GasSampler;                // THIS FRAME'S MARCH (PREMULTIPLIED)
uniform sampler2D GasDistanceSampler;        // ITS DISTANCES (SEE fart_gas.fsh)
uniform sampler2D HistorySampler;            // LAST FRAME'S RESULT
uniform sampler2D PreviousProjectionSampler; // LAST FRAME'S ProjMat, ONE COLUMN PER TEXEL

layout(location = 0) in vec4 unprojectBase;
layout(location = 1) in vec4 unprojectDepth;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(GasSampler, 0);
    vec4 current = texelFetch(GasSampler, texel, 0);

    // THIS PIXEL'S NDC, AND HOW MUCH NDC ONE PIXEL IS. TAKING BOTH FROM THE RASTERIZER (RATHER
    // THAN ASSUMING WHICH WAY UP THE SCREEN IS) KEEPS THE REPROJECTION RIGHT ON EVERY BACKEND.
    // (ProjMat UNDOES THE VERTEX SHADER'S UNPROJECTION EXACTLY: w IS 1.)
    vec2 ndc = (ProjMat * unprojectBase).xy;
    vec2 ndcPerPixel = vec2(dFdx(ndc.x), dFdy(ndc.y));

    if (CameraMotion.w <= 0.0) {
        fragColor = current;
        return;
    }

    vec4 minNeighbour = current;
    vec4 maxNeighbour = current;
    for (int neighbourIndex = 0; neighbourIndex < 9; neighbourIndex++) {
        ivec2 offset = ivec2(neighbourIndex % 3, neighbourIndex / 3) - 1;
        vec4 neighbour = texelFetch(GasSampler, clamp(texel + offset, ivec2(0), size - 1), 0);
        minNeighbour = min(minNeighbour, neighbour);
        maxNeighbour = max(maxNeighbour, neighbour);
    }

    // 1. WHERE THE GAS (OR, WITH NONE, THE SCENE) WAS RELATIVE TO LAST FRAME'S CAMERA...
    vec3 rayOrigin;
    vec3 rayDir;
    viewRay(unprojectBase, unprojectDepth, rayOrigin, rayDir);
    float gasT = texelFetch(GasDistanceSampler, texel, 0).y;
    vec3 previousWorld = rayOrigin + rayDir * gasT + CameraMotion.xyz;

    // ...AND WHERE LAST FRAME'S PROJECTION (BOBBING INCLUDED) PUT IT ON SCREEN.
    mat4 previousProjection = mat4(
        texelFetch(PreviousProjectionSampler, ivec2(0, 0), 0),
        texelFetch(PreviousProjectionSampler, ivec2(1, 0), 0),
        texelFetch(PreviousProjectionSampler, ivec2(2, 0), 0),
        texelFetch(PreviousProjectionSampler, ivec2(3, 0), 0));
    vec4 previousClip = previousProjection * vec4(mat3(PrevWorldToView) * previousWorld, 1.0);
    if (previousClip.w <= 1e-4) {
        fragColor = current;
        return;
    }

    vec2 previousPixel = gl_FragCoord.xy + (previousClip.xy / previousClip.w - ndc) / ndcPerPixel;
    vec2 uv = previousPixel / vec2(size);
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        fragColor = current;
        return;
    }

    // 2 + 3.
    vec4 history = clamp(texture(HistorySampler, uv), minNeighbour, maxNeighbour);
    fragColor = mix(current, history, CameraMotion.w);
}
