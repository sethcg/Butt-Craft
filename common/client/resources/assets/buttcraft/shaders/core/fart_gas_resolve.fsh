#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <buttcraft:fart_gas_common.glsl>

// BLENDS THE JITTERED MARCH WITH LAST FRAME'S RESULT, SO EACH PIXEL AVERAGES MANY MORE SAMPLES:
//
//   1. FIND WHERE THE GAS ON THIS PIXEL'S RAY WAS ON SCREEN LAST FRAME
//   2. READ LAST FRAME'S RESULT THERE AND CLAMP IT TO THIS FRAME'S 3x3 NEIGHBOURHOOD, SO GAS THAT
//      MOVED OR CHANGED DOESN'T LEAVE A TRAIL
//   3. BLEND

uniform sampler2D GasSampler;
uniform sampler2D GasDistanceSampler;
uniform sampler2D HistorySampler;
uniform sampler2D PreviousProjectionSampler; // ONE COLUMN PER TEXEL

layout(location = 0) in vec4 unprojectBase;
layout(location = 1) in vec4 unprojectDepth;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(GasSampler, 0);
    vec4 current = texelFetch(GasSampler, texel, 0);

    // TAKEN FROM THE RASTERIZER SO THE REPROJECTION IS RIGHT ON EVERY BACKEND, WHICHEVER WAY UP THE
    // SCREEN IS. (ProjMat UNDOES THE VERTEX SHADER'S UNPROJECTION EXACTLY: w IS 1.)
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

    // WITH NO GAS, gasT IS THE DISTANCE TO THE SCENE
    vec3 rayOrigin;
    vec3 rayDir;
    viewRay(unprojectBase, unprojectDepth, rayOrigin, rayDir);
    float gasT = texelFetch(GasDistanceSampler, texel, 0).y;
    vec3 previousWorld = rayOrigin + rayDir * gasT + CameraMotion.xyz;

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

    vec4 history = clamp(texture(HistorySampler, uv), minNeighbour, maxNeighbour);
    fragColor = mix(current, history, CameraMotion.w);
}
