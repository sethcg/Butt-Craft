package com.github.sethcg.buttcraft.client.gas;

import java.util.List;
import java.util.Optional;

import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import com.github.sethcg.buttcraft.ButtCraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;

/**
 * REDUCED-RESOLUTION GPU RAY-MARCH PASS FOR THE FART GAS (SEE {@link FartGasSettings}).
 *
 * <p>THE ONLY VERSION-SPECIFIC CLASS: MOJANG'S GPU API CHANGES BETWEEN RELEASES, SO EACH
 * MINECRAFT VERSION HAS ITS OWN COPY. EVERYTHING ELSE (SIMULATION, UNIFORMS, SHADERS) IS COMMON.
 *
 * <p>RUNS AT {@code END_MAIN}, AFTER EVERY RENDER PASS OF THE MAIN STAGE HAS CLOSED
 * (WORKS WITH BOTH CLASSIC AND IMPROVED TRANSPARENCY). TWO PASSES, BOTH DRAWING ONE
 * QUAD SHRUNK TO THE PUFFS' SCREEN BOUNDS IN THE VERTEX SHADER:
 *
 * <ol>
 *   <li>MARCH: RAY-MARCHES INTO A CLEARED REDUCED-RESOLUTION TARGET (PREMULTIPLIED ALPHA,
 *   NO BLENDING), SAMPLING THE MAIN DEPTH TEXTURE.</li>
 *   <li>COMPOSITE: DEPTH-AWARE UPSCALE, BLENDED ONTO THE MAIN COLOR TARGET WITH
 *   PREMULTIPLIED-ALPHA BLENDING.</li>
 * </ol>
 *
 * <p>AT FULL RESOLUTION THE UPSCALE WOULD ONLY COPY PIXELS, SO A SINGLE PASS MARCHES
 * STRAIGHT ONTO THE MAIN COLOR TARGET WITH PREMULTIPLIED-ALPHA BLENDING INSTEAD.
 *
 * <p>DEPTH IS NEVER ATTACHED, SO THERE IS NO READ/WRITE FEEDBACK LOOP.
 */
public final class FartGasRenderer {

    // PRE-BAKED, SEAMLESSLY TILING 64x64x64 NOISE VOLUME STORED AS A 2D ATLAS:
    // 64 SLICES OF 64x64 WITH A 1px WRAPPED GUTTER, IN AN 8x8 GRID (528x528 RGBA).
    //   R = PERLIN-WORLEY, G = WORLEY FBM (LOW), B = WORLEY FBM (HIGH), A = PERLIN FBM
    private static final Identifier NOISE_TEXTURE = ButtCraft.id("textures/fart_gas_noise.png");

    // THE SAME VOLUME WITH THE SHADER'S SHAPE/DETAIL CHANNEL BLENDS PRE-BAKED FOR TWO
    // NEIGHBOURING SLICES, SO EACH OF THOSE LOOKUPS IS ONE FETCH.
    private static final Identifier SHAPE_TEXTURE = ButtCraft.id("textures/fart_gas_shape.png");

    // THE MARCH INTO THE CLEARED REDUCED-RESOLUTION TARGET (NO BLENDING)...
    public static final RenderPipeline PIPELINE = marchPipeline("pipeline/fart_gas", Optional.empty());

    // ...OR BLENDED STRAIGHT ONTO THE SCENE (FULL RESOLUTION ONLY).
    public static final RenderPipeline DIRECT_PIPELINE = marchPipeline(
        "pipeline/fart_gas_direct",
        Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA));

    public static final RenderPipeline COMPOSITE_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder()
            .withLocation(ButtCraft.id("pipeline/fart_gas_composite"))
            .withVertexShader(ButtCraft.id("core/fart_gas"))
            .withFragmentShader(ButtCraft.id("core/fart_gas_composite"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("FartGasInfo", UniformType.UNIFORM_BUFFER)
                .withUniform("GasSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .build())
            .withColorTargetState(new ColorTargetState(
                Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
                GpuFormat.RGBA8_UNORM,
                ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build());

    private static final Optional<Vector4fc> CLEAR_TRANSPARENT = Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));

    private final FartGasSimulation simulation;
    private @Nullable MappableRingBuffer ubo;
    private @Nullable TextureTarget gasTarget;
    private boolean warnedMissingPipeline;

    public FartGasRenderer(FartGasSimulation simulation) {
        this.simulation = simulation;
    }

    /** LOADS THIS CLASS SO THE PIPELINES ARE REGISTERED BEFORE THE FIRST RESOURCE RELOAD COMPILES SHADERS. */
    public static void initialize() {
    }

    public void render(LevelRenderContext context) {
        List<FartGasSimulation.Puff> puffs = this.simulation.puffs();
        if (puffs.isEmpty()) {
            return;
        }

        CompiledRenderPipeline pipeline = RenderSystem.getCompiledPipelineNullable(PIPELINE);
        CompiledRenderPipeline compositePipeline = RenderSystem.getCompiledPipelineNullable(COMPOSITE_PIPELINE);
        CompiledRenderPipeline directPipeline = RenderSystem.getCompiledPipelineNullable(DIRECT_PIPELINE);
        if (pipeline == null || compositePipeline == null || directPipeline == null) {
            if (!this.warnedMissingPipeline) {
                this.warnedMissingPipeline = true;
                ButtCraft.LOGGER.warn("FART GAS PIPELINE IS NOT AVAILABLE (SHADER FAILED TO COMPILE?). GAS WILL NOT RENDER.");
            }
            return;
        }

        TextureManager textures = Minecraft.getInstance().getTextureManager();
        GpuTextureView noise = textures.getTexture(NOISE_TEXTURE).getTextureView();
        GpuTextureView shape = textures.getTexture(SHAPE_TEXTURE).getTextureView();

        // THE PIPELINE READS THE LEVEL PROJECTION AND FOG BUFFERS SET UP BY VANILLA.
        if (RenderSystem.getProjectionMatrixBuffer() == null || RenderSystem.getShaderFog() == null) {
            return;
        }

        RenderTarget mainTarget = context.gameRenderer().mainRenderTarget();
        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorView == null || depthView == null) {
            return;
        }

        if (this.ubo == null) {
            this.ubo = new MappableRingBuffer(() -> "ButtCraft fart gas UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FartGasUniforms.SIZE);
        }

        // READ ONCE SO THE UNIFORMS AND THE TARGET SIZE AGREE EVEN IF THE SETTING CHANGES MID-FRAME.
        FartGasSettings.Values quality = FartGasSettings.current();

        GpuBuffer buffer = this.ubo.currentBuffer();
        int visiblePuffs;
        try (GpuBufferSlice.MappedView view = buffer.slice().map(false, true)) {
            visiblePuffs = FartGasUniforms.write(view.data(), context.levelState(), puffs, quality);
        }

        // EVERY PUFF IS TOO FAINT TO SEE (E.G. THE TAIL END OF A FADING CLOUD).
        if (visiblePuffs == 0) {
            return;
        }

        // AT FULL RESOLUTION THE MARCH BLENDS STRAIGHT ONTO THE SCENE AND NEEDS NO GAS TARGET.
        boolean direct = quality.resolutionDivisor() == 1;
        GpuTextureView gasView = null;
        if (direct) {
            this.destroyGasTarget();
        } else {
            gasView = this.gasTarget(mainTarget.width, mainTarget.height, quality).getColorTextureView();
            if (gasView == null) {
                return;
            }
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        // 1. MARCH. AT REDUCED RESOLUTION THE CLEAR MATTERS: THE UPSCALE READS TEXELS JUST
        // OUTSIDE THE QUAD, WHICH MUST BE EMPTY RATHER THAN LAST FRAME'S GAS.
        try (RenderPass pass = direct
                ? encoder.createRenderPass(() -> "ButtCraft fart gas march (direct)", colorView, Optional.empty())
                : encoder.createRenderPass(() -> "ButtCraft fart gas march", gasView, CLEAR_TRANSPARENT)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(direct ? directPipeline : pipeline);
            pass.setUniform("FartGasInfo", buffer);
            pass.setUniform("NoiseSampler", noise, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setUniform("ShapeSampler", shape, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setUniform("DepthSampler", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(6, 1, 0, 0);
        }

        // 2. DEPTH-AWARE UPSCALE ONTO THE SCENE.
        if (!direct) {
            try (RenderPass pass = encoder.createRenderPass(() -> "ButtCraft fart gas composite", colorView, Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setPipeline(compositePipeline);
                pass.setUniform("FartGasInfo", buffer);
                pass.setUniform("GasSampler", gasView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.setUniform("DepthSampler", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                pass.draw(6, 1, 0, 0);
            }
        }

        this.ubo.rotate();
    }

    /** THE RAY-MARCH PIPELINE, WRITING TO AN RGBA8 TARGET WITH THE GIVEN BLENDING. */
    private static RenderPipeline marchPipeline(String location, Optional<BlendFunction> blend) {
        return RenderPipelines.registerOptional(
            RenderPipeline.builder()
                .withLocation(ButtCraft.id(location))
                .withVertexShader(ButtCraft.id("core/fart_gas"))
                .withFragmentShader(ButtCraft.id("core/fart_gas"))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.FOG)
                .withBindGroupLayout(BindGroupLayout.builder()
                    .withUniform("FartGasInfo", UniformType.UNIFORM_BUFFER)
                    .withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("ShapeSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                    .build())
                .withColorTargetState(new ColorTargetState(
                    blend,
                    GpuFormat.RGBA8_UNORM,
                    ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build());
    }

    public void close() {
        if (this.ubo != null) {
            this.ubo.close();
            this.ubo = null;
        }

        this.destroyGasTarget();
    }

    private void destroyGasTarget() {
        if (this.gasTarget != null) {
            this.gasTarget.destroyBuffers();
            this.gasTarget = null;
        }
    }

    /** REDUCED-RESOLUTION MARCH TARGET, (RE)SIZED TO FOLLOW THE MAIN TARGET. */
    private TextureTarget gasTarget(int fullWidth, int fullHeight, FartGasSettings.Values quality) {
        int divisor = quality.resolutionDivisor();
        int width = Math.max((fullWidth + divisor - 1) / divisor, 1);
        int height = Math.max((fullHeight + divisor - 1) / divisor, 1);
        if (this.gasTarget == null) {
            this.gasTarget = new TextureTarget("ButtCraft fart gas", width, height, GpuFormat.RGBA8_UNORM, null);
        } else if (this.gasTarget.width != width || this.gasTarget.height != height) {
            this.gasTarget.resize(width, height);
        }

        return this.gasTarget;
    }
}
