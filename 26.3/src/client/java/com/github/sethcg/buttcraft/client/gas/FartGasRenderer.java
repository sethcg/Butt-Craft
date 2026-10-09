package com.github.sethcg.buttcraft.client.gas;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import com.github.sethcg.buttcraft.ButtCraft;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
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
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/**
 * REDUCED-RESOLUTION GPU RAY-MARCH PASS FOR THE FART GAS (SEE {@link FartGasSettings}).
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
 * <p>DEPTH IS NEVER ATTACHED, SO THERE IS NO READ/WRITE FEEDBACK LOOP.
 */
public final class FartGasRenderer {

    // CAMERA POSITION IS WRAPPED BY THE LCM OF THE SHADER'S NOISE PERIODS (4, 1.5, 0.5 BLOCKS).
    private static final double NOISE_PERIOD = 12.0;
    private static final double TIME_PERIOD_SECONDS = 2400.0;

    private static final Vector3fc GAS_ALBEDO = new Vector3f(0.64F, 0.74F, 0.40F);
    private static final float GAS_DENSITY = 1.6F;

    // PRE-BAKED, SEAMLESSLY TILING 64x64x64 NOISE VOLUME STORED AS A 2D ATLAS:
    // 64 SLICES OF 64x64 WITH A 1px WRAPPED GUTTER, IN AN 8x8 GRID (528x528 RGBA).
    //   R = PERLIN-WORLEY, G = WORLEY FBM (LOW), B = WORLEY FBM (HIGH), A = PERLIN FBM
    private static final Identifier NOISE_TEXTURE = ButtCraft.id("textures/fart_gas_noise.png");

    private static final int UBO_SIZE;

    static {
        Std140SizeCalculator size = new Std140SizeCalculator()
            .putMat4f()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putIVec4();
        for (int i = 0; i < FartGasSimulation.MAX_PUFFS * 2; i++) {
            size.putVec4();
        }
        UBO_SIZE = size.get();
    }

    public static final RenderPipeline PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder()
            .withLocation(ButtCraft.id("pipeline/fart_gas"))
            .withVertexShader(ButtCraft.id("core/fart_gas"))
            .withFragmentShader(ButtCraft.id("core/fart_gas"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("FartGasInfo", UniformType.UNIFORM_BUFFER)
                .withUniform("NoiseSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .build())
            .withColorTargetState(new ColorTargetState(
                Optional.empty(),
                GpuFormat.RGBA8_UNORM,
                ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build());

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

    public void render(LevelRenderContext context) {
        List<FartGasSimulation.Puff> puffs = this.simulation.puffs();
        if (puffs.isEmpty()) {
            return;
        }

        CompiledRenderPipeline pipeline = RenderSystem.getCompiledPipelineNullable(PIPELINE);
        CompiledRenderPipeline compositePipeline = RenderSystem.getCompiledPipelineNullable(COMPOSITE_PIPELINE);
        if (pipeline == null || compositePipeline == null) {
            if (!this.warnedMissingPipeline) {
                this.warnedMissingPipeline = true;
                ButtCraft.LOGGER.warn("FART GAS PIPELINE IS NOT AVAILABLE (SHADER FAILED TO COMPILE?). GAS WILL NOT RENDER.");
            }
            return;
        }

        GpuTextureView noise = Minecraft.getInstance().getTextureManager().getTexture(NOISE_TEXTURE).getTextureView();

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
            this.ubo = new MappableRingBuffer(() -> "ButtCraft fart gas UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UBO_SIZE);
        }

        // READ ONCE SO THE UNIFORMS AND THE TARGET SIZE AGREE EVEN IF THE SETTING CHANGES MID-FRAME.
        FartGasSettings.Values quality = FartGasSettings.current();

        GpuBuffer buffer = this.ubo.currentBuffer();
        try (GpuBufferSlice.MappedView view = buffer.slice().map(false, true)) {
            this.writeUniforms(view.data(), context.levelState(), puffs, quality);
        }

        GpuTextureView gasView = this.gasTarget(mainTarget.width, mainTarget.height, quality).getColorTextureView();
        if (gasView == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

        // 1. MARCH AT REDUCED RESOLUTION. THE CLEAR MATTERS: THE UPSCALE READS TEXELS JUST
        // OUTSIDE THE QUAD, WHICH MUST BE EMPTY RATHER THAN LAST FRAME'S GAS.
        try (RenderPass pass = encoder.createRenderPass(() -> "ButtCraft fart gas march", gasView, CLEAR_TRANSPARENT)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(pipeline);
            pass.setUniform("FartGasInfo", buffer);
            pass.setUniform("NoiseSampler", noise, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.setUniform("DepthSampler", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(6, 1, 0, 0);
        }

        // 2. DEPTH-AWARE UPSCALE ONTO THE SCENE.
        try (RenderPass pass = encoder.createRenderPass(() -> "ButtCraft fart gas composite", colorView, Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(compositePipeline);
            pass.setUniform("FartGasInfo", buffer);
            pass.setUniform("GasSampler", gasView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("DepthSampler", depthView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(6, 1, 0, 0);
        }

        this.ubo.rotate();
    }

    public void close() {
        if (this.ubo != null) {
            this.ubo.close();
            this.ubo = null;
        }

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

    private void writeUniforms(ByteBuffer data, LevelRenderState levelState, List<FartGasSimulation.Puff> puffs, FartGasSettings.Values quality) {
        CameraRenderState camera = levelState.cameraRenderState;
        SkyRenderState sky = levelState.skyRenderState;
        float partial = levelState.worldPartialTicks;
        Vec3 cameraPos = camera.pos;

        Matrix4f viewToWorld = new Matrix4f(camera.viewRotationMatrix).invert();
        double time = ((levelState.gameTime % (long) (TIME_PERIOD_SECONDS * 20.0)) + partial) / 20.0;

        Std140Builder builder = Std140Builder.intoBuffer(data)
            .putMat4f(viewToWorld)
            .putVec4(
                (float) Mth.positiveModulo(cameraPos.x, NOISE_PERIOD),
                (float) Mth.positiveModulo(cameraPos.y, NOISE_PERIOD),
                (float) Mth.positiveModulo(cameraPos.z, NOISE_PERIOD),
                (float) time);

        this.putLighting(builder, sky);

        builder.putVec4(GAS_ALBEDO.x(), GAS_ALBEDO.y(), GAS_ALBEDO.z(), GAS_DENSITY);

        int count = Math.min(puffs.size(), FartGasSimulation.MAX_PUFFS);
        builder.putIVec4(count, quality.maxViewSteps(), quality.shadowSteps(), quality.resolutionDivisor());

        for (int i = 0; i < FartGasSimulation.MAX_PUFFS; i++) {
            if (i < count) {
                FartGasSimulation.Puff puff = puffs.get(i);
                builder.putVec4(
                    (float) (puff.x(partial) - cameraPos.x),
                    (float) (puff.y(partial) - cameraPos.y),
                    (float) (puff.z(partial) - cameraPos.z),
                    puff.radius(partial));
                builder.putVec4(puff.density(partial), puff.skyLight(), puff.blockLight(), puff.seed());
            } else {
                builder.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
                builder.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
            }
        }
    }

    /** DIRECTION/COLOR OF THE DOMINANT CELESTIAL LIGHT AND THE SKY AMBIENT COLOR. */
    private void putLighting(Std140Builder builder, SkyRenderState sky) {
        if (sky.skybox != DimensionType.Skybox.OVERWORLD) {
            // NO SUN: FLAT, DIMENSION-TINTED AMBIENT (PUFF SKY LIGHT IS FORCED TO 1 IN SKYLESS DIMENSIONS).
            boolean end = sky.skybox == DimensionType.Skybox.END;
            builder.putVec4(0.0F, 1.0F, 0.0F, 0.0F);
            builder.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
            builder.putVec4(end ? 0.30F : 0.46F, end ? 0.26F : 0.34F, end ? 0.38F : 0.27F, 0.0F);
            return;
        }

        float sunAngle = sky.sunAngle;
        Vector3f sunDir = new Vector3f(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0.0F);
        float sunHeight = sunDir.y;
        float clearSky = sky.rainBrightness;

        // SAME SHAPE AS VANILLA'S SKY DARKENING, SO THE GAS DIMS IN STEP WITH THE TERRAIN.
        float daylight = Mth.clamp(sunHeight * 2.0F + 0.2F, 0.0F, 1.0F);

        Vector3f lightDir;
        Vector3f lightColor;
        float strength;
        if (sunHeight > -0.08F) {
            lightDir = sunDir;
            strength = 1.05F * daylight * daylight * Mth.lerp(clearSky, 0.2F, 1.0F);
            // WARM, LOW SUN AT DAWN/DUSK; NEUTRAL WHITE AT NOON.
            float noon = smoothstep(0.0F, 0.45F, sunHeight);
            lightColor = new Vector3f(1.0F, 0.52F, 0.28F).lerp(new Vector3f(1.0F, 0.96F, 0.88F), noon);
        } else {
            lightDir = sunDir.negate(new Vector3f());
            strength = 0.16F * smoothstep(0.08F, 0.3F, -sunHeight) * clearSky;
            lightColor = new Vector3f(0.55F, 0.66F, 0.95F);
        }

        Vector3fc skyColor = sky.skyColor != null ? sky.skyColor : new Vector3f(0.47F, 0.65F, 1.0F);
        builder.putVec4(lightDir.x, lightDir.y, lightDir.z, strength);
        builder.putVec4(lightColor.x, lightColor.y, lightColor.z, 0.0F);
        // AMBIENT FOLLOWS THE SKY COLOR, WITH A FAINT MOONLIGHT FLOOR SO NIGHT GAS STAYS READABLE.
        builder.putVec4(
            skyColor.x() * 0.55F + 0.035F,
            skyColor.y() * 0.55F + 0.040F,
            skyColor.z() * 0.55F + 0.060F,
            0.0F);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
