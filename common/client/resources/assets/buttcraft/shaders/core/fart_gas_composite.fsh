#version 330
#extension GL_ARB_separate_shader_objects : require

#include <buttcraft:fart_gas_common.glsl>

// DEPTH-AWARE UPSCALE: EACH PIXEL TAKES THE FOUR NEAREST LOW-RES TEXELS WITH BILINEAR WEIGHTS, SCALED
// DOWN WHEN THE SCENE DEPTH THAT TEXEL MARCHED AGAINST DIFFERS FROM THIS PIXEL'S. THAT KEEPS GAS FROM
// BLEEDING ACROSS SILHOUETTES (E.G. A HALO AROUND A BLOCK IN FRONT OF THE CLOUD)

uniform sampler2D GasSampler;
// R = VIEW DISTANCE THE TEXEL MARCHED AGAINST, G = ITS GAS'S DISTANCE. TEXELS OUTSIDE THE MARCHER'S
// QUADS STAY CLEARED TO 0: THEY HOLD NO GAS, SO THEIR SMALL WEIGHT DOESN'T MATTER
uniform sampler2D GasDistanceSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec4 unprojectBase;
layout(location = 1) in vec4 unprojectDepth;

layout(location = 0) out vec4 fragColor;

// A TAP THAT MARCHED AGAINST SCENERY MORE THAN THIS MUCH (RELATIVE) FARTHER AWAY THAN THIS PIXEL'S MAY
// HOLD GAS THAT IS HIDDEN HERE. WITH ONLY SUCH TAPS, THE RESULT FADES OUT INSTEAD OF RENORMALISING
// NEARER TAPS NEVER HOLD HIDDEN GAS AND STILL RENORMALISE, AS DO FARTHER TAPS WHOSE GAS SITS CLEARLY
// IN FRONT OF THIS PIXEL'S SCENERY (OTHERWISE GROUND NEXT TO SKY LEAVES A LIGHT HORIZON LINE)
const float HALO_TOLERANCE = 0.1;
const float DEPTH_WEIGHT_BIAS = 0.02;

void main() {
    ivec2 gasSize = textureSize(GasSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    int divisor = max(Counts.w, 1);

    // MEASURED THE SAME WAY AS THE MARCHER'S (SKY = 1e6)
    float deviceDepth = texelFetch(DepthSampler, pixel, 0).r;
    float centerDistance = deviceDepth > 0.0 ? length(unproject(unprojectBase, unprojectDepth, deviceDepth)) : 1e6;

    // LOW-RES TEXEL I COVERS FULL-RES PIXELS [N * I, N * I + N)
    vec2 gasPos = gl_FragCoord.xy / float(divisor) - 0.5;
    ivec2 base = ivec2(floor(gasPos));
    vec2 fraction = gasPos - vec2(base);

    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    float haloFloor = 0.0;
    for (int tap = 0; tap < 4; tap++) {
        ivec2 offset = ivec2(tap & 1, tap >> 1);
        ivec2 texel = clamp(base + offset, ivec2(0), gasSize - 1);
        vec2 bilinear = mix(1.0 - fraction, fraction, vec2(offset));
        float bilinearWeight = bilinear.x * bilinear.y;

        vec4 tapGas = texelFetch(GasSampler, texel, 0);
        vec2 tapDistances = texelFetch(GasDistanceSampler, texel, 0).rg;
        float tapDistance = tapDistances.x;
        float relative = abs(tapDistance - centerDistance) / min(centerDistance, 1e5);
        float weight = bilinearWeight / (relative + DEPTH_WEIGHT_BIAS);
        // Y IS THE GAS'S ABSORPTION-WEIGHTED DISTANCE, SO SOME OF IT LIES PAST THAT: KEEP A MARGIN
        // (AN EMPTY TAP'S Y IS ITS SCENE, NOT GAS, SO IT KEEPS THE FADE.)
        bool gasInFront = tapGas.a > 0.0 && tapDistances.y * (1.0 + HALO_TOLERANCE) < centerDistance;
        if (tapDistance > centerDistance && !gasInFront) {
            haloFloor += bilinearWeight / (HALO_TOLERANCE + DEPTH_WEIGHT_BIAS);
        }

        sum += tapGas * weight;
        weightSum += weight;
    }

    vec4 gas = sum / max(max(weightSum, haloFloor), 1e-6);
    if (gas.a < 0.003) {
        discard;
    }

    // THE MARCHER'S OWN DITHER IS BLOCKY AFTER THE UPSCALE, AND TEMPORAL SMOOTHING AVERAGES IT AWAY
    gas.rgb += (interleavedGradientNoise(gl_FragCoord.xy, Frame.x) - 0.5) / 255.0;
    fragColor = vec4(max(gas.rgb, vec3(0.0)), gas.a);
}
