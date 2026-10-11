#version 330
#extension GL_ARB_separate_shader_objects : require

// =============================================================================
// BUTTCRAFT VOLUMETRIC FART GAS: DEPTH-AWARE UPSCALE
//
// THE RAY MARCHER (fart_gas.fsh) RUNS AT 1/N RESOLUTION. THIS PASS BLENDS ITS
// PREMULTIPLIED RESULT ONTO THE FULL-RESOLUTION SCENE. EACH PIXEL TAKES THE FOUR
// NEAREST LOW-RES TEXELS WITH BILINEAR WEIGHTS, SCALED DOWN WHEN THE SCENE DEPTH
// THAT TEXEL MARCHED AGAINST DIFFERS FROM THIS PIXEL'S. THAT KEEPS GAS FROM
// BLEEDING ACROSS SILHOUETTES (E.G. A HALO AROUND A BLOCK IN FRONT OF THE CLOUD).
// =============================================================================

uniform sampler2D GasSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec4 unprojectBase;
layout(location = 2) in vec4 unprojectDepth;

layout(location = 0) out vec4 fragColor;

float deviceToNdcDepth(float deviceDepth) {
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    return deviceDepth;
    #else
    return deviceDepth * 2.0 - 1.0;
    #endif
}

// APPROXIMATE VIEW DISTANCE FOR A DEVICE DEPTH (REVERSED-Z: 0 = SKY). USES THIS
// PIXEL'S UNPROJECTION FOR ALL TAPS; THE TINY XY ERROR DOESN'T MATTER FOR WEIGHTING.
float viewDistance(float deviceDepth) {
    if (deviceDepth <= 0.0) {
        return 1e6;
    }

    vec4 h = unprojectBase + deviceToNdcDepth(deviceDepth) * unprojectDepth;
    return length(h.xyz / h.w);
}

void main() {
    ivec2 fullSize = textureSize(DepthSampler, 0);
    ivec2 gasSize = textureSize(GasSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);

    // THE GAS TARGET IS CEIL(FULL / N) WIDE, SO ROUNDING THE RATIO RECOVERS N.
    int divisor = max(int(float(fullSize.x) / float(gasSize.x) + 0.5), 1);

    float centerDistance = viewDistance(texelFetch(DepthSampler, pixel, 0).r);

    // LOW-RES TEXEL I COVERS FULL-RES PIXELS [N * I, N * I + N), SO THIS PIXEL SITS AT:
    vec2 gasPos = gl_FragCoord.xy / float(divisor) - 0.5;
    ivec2 base = ivec2(floor(gasPos));
    vec2 f = gasPos - vec2(base);

    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 offset = ivec2(i & 1, i >> 1);
        ivec2 texel = clamp(base + offset, ivec2(0), gasSize - 1);
        vec2 bilinear = mix(1.0 - f, f, vec2(offset));

        // THE MARCHER CLIPPED THIS TEXEL AGAINST THE DEPTH AT THE CENTER OF ITS BLOCK.
        float tapDepth = texelFetch(DepthSampler, min(texel * divisor + divisor / 2, fullSize - 1), 0).r;
        float relative = abs(viewDistance(tapDepth) - centerDistance) / min(centerDistance, 1e5);
        float w = bilinear.x * bilinear.y / (relative + 0.02);

        sum += texelFetch(GasSampler, texel, 0) * w;
        weightSum += w;
    }

    vec4 gas = sum / max(weightSum, 1e-6);
    if (gas.a < 0.003) {
        discard;
    }

    fragColor = gas;
}
