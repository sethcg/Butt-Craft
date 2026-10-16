#version 330
#extension GL_ARB_separate_shader_objects : require

#include <buttcraft:fart_gas_common.glsl>

// =============================================================================
// BUTTCRAFT VOLUMETRIC FART GAS: DEPTH-AWARE UPSCALE
//
// THE RAY MARCHER (fart_gas.fsh) RUNS AT 1/N RESOLUTION. THIS PASS BLENDS ITS
// PREMULTIPLIED RESULT ONTO THE FULL-RESOLUTION SCENE. EACH PIXEL TAKES THE FOUR
// NEAREST LOW-RES TEXELS WITH BILINEAR WEIGHTS, SCALED DOWN WHEN THE SCENE DEPTH
// THAT TEXEL MARCHED AGAINST DIFFERS FROM THIS PIXEL'S. THAT KEEPS GAS FROM
// BLEEDING ACROSS SILHOUETTES (E.G. A HALO AROUND A BLOCK IN FRONT OF THE CLOUD).
// NOT USED AT FULL RESOLUTION: THE MARCHER THEN BLENDS STRAIGHT ONTO THE SCENE.
// =============================================================================

uniform sampler2D GasSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec4 unprojectBase;
layout(location = 1) in vec4 unprojectDepth;

layout(location = 0) out vec4 fragColor;

// A TAP THAT MARCHED AGAINST SCENERY MORE THAN THIS MUCH (RELATIVE) FARTHER AWAY THAN THIS
// PIXEL'S MAY HOLD GAS THAT IS HIDDEN HERE. WHEN ONLY SUCH TAPS ARE AVAILABLE, THE RESULT
// FADES OUT INSTEAD OF BEING RENORMALISED BACK TO FULL STRENGTH. TAPS THAT MARCHED
// AGAINST NEARER SCENERY HOLD LESS GAS THAN THIS PIXEL SHOULD, NEVER GAS IT CAN'T SEE,
// SO THEY ARE STILL RENORMALISED.
const float HALO_TOLERANCE = 0.1;
const float DEPTH_WEIGHT_BIAS = 0.02;

// APPROXIMATE VIEW DISTANCE FOR A DEVICE DEPTH (REVERSED-Z: 0 = SKY). USES THIS
// PIXEL'S UNPROJECTION FOR ALL TAPS; THE TINY XY ERROR DOESN'T MATTER FOR WEIGHTING.
float viewDistance(float deviceDepth) {
    if (deviceDepth <= 0.0) {
        return 1e6;
    }

    return length(unproject(unprojectBase, unprojectDepth, deviceDepth));
}

void main() {
    ivec2 fullSize = textureSize(DepthSampler, 0);
    ivec2 gasSize = textureSize(GasSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int divisor = max(Counts.w, 1);

    float centerDistance = viewDistance(texelFetch(DepthSampler, pixel, 0).r);

    // LOW-RES TEXEL I COVERS FULL-RES PIXELS [N * I, N * I + N), SO THIS PIXEL SITS AT:
    vec2 gasPos = gl_FragCoord.xy / float(divisor) - 0.5;
    ivec2 base = ivec2(floor(gasPos));
    vec2 f = gasPos - vec2(base);

    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    float haloFloor = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        ivec2 texel = clamp(base + offset, ivec2(0), gasSize - 1);
        vec2 bilinear = mix(1.0 - f, f, vec2(offset));
        float b = bilinear.x * bilinear.y;

        // THE MARCHER CLIPPED THIS TEXEL AGAINST THE DEPTH AT THE CENTER OF ITS BLOCK.
        float tapDepth = texelFetch(DepthSampler, min(texel * divisor + divisor / 2, fullSize - 1), 0).r;
        float tapDistance = viewDistance(tapDepth);
        float relative = abs(tapDistance - centerDistance) / min(centerDistance, 1e5);
        float w = b / (relative + DEPTH_WEIGHT_BIAS);
        if (tapDistance > centerDistance) {
            haloFloor += b / (HALO_TOLERANCE + DEPTH_WEIGHT_BIAS);
        }

        sum += texelFetch(GasSampler, texel, 0) * w;
        weightSum += w;
    }

    vec4 gas = sum / max(max(weightSum, haloFloor), 1e-6);
    if (gas.a < 0.003) {
        discard;
    }

    fragColor = gas;
}
