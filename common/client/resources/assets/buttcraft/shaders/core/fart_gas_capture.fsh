#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// =============================================================================
// BUTTCRAFT VOLUMETRIC FART GAS: PROJECTION CAPTURE
//
// COPIES THIS FRAME'S ProjMat INTO A 4x1 FLOAT TARGET, ONE COLUMN PER TEXEL, SO NEXT
// FRAME'S TEMPORAL SMOOTHING (fart_gas_resolve.fsh) CAN REPROJECT WITH IT. VANILLA FOLDS
// VIEW BOBBING INTO THAT MATRIX WITHOUT KEEPING A COPY THE MOD CAN READ, AND ITS BUFFER
// CAN'T BE COPIED FROM, SO A DRAW IS THE ONLY WAY TO KEEP IT.
// =============================================================================

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = ProjMat[int(gl_FragCoord.x)];
}
