package com.github.sethcg.buttcraft.client.gas;

import java.util.List;
import java.util.Optional;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import com.github.sethcg.buttcraft.ButtCraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * REDUCED-RESOLUTION GPU RAY-MARCH PASS FOR THE FART GAS (SEE {@link FartGasSettings}).
 *
 * <p>THE ONLY VERSION-SPECIFIC CLASS: MOJANG'S GPU API CHANGES BETWEEN RELEASES, SO EACH
 * MINECRAFT VERSION HAS ITS OWN COPY. EVERYTHING ELSE (SIMULATION, UNIFORMS, SHADERS) IS COMMON.
 *
 * <p>RUNS AT {@code END_MAIN}, AFTER EVERY RENDER PASS OF THE MAIN STAGE HAS CLOSED
 * (WORKS WITH BOTH CLASSIC AND IMPROVED TRANSPARENCY). EACH PASS DRAWS QUADS SHRUNK TO THE
 * PUFFS' SCREEN BOUNDS IN THE VERTEX SHADER:
 *
 * <ol>
 *   <li>MARCH: RAY-MARCHES INTO CLEARED REDUCED-RESOLUTION TARGETS (NO BLENDING): THE GAS
 *   (PREMULTIPLIED ALPHA) AND THE DISTANCES THE LATER PASSES NEED, SAMPLING THE MAIN DEPTH
 *   TEXTURE. ONE QUAD PER SEPARATE CLOUD.</li>
 *   <li>RESOLVE (TEMPORAL SMOOTHING ONLY): BLENDS THE MARCH WITH LAST FRAME'S RESULT,
 *   REPROJECTED, INTO ONE OF TWO PING-PONGED HISTORY TARGETS.</li>
 *   <li>COMPOSITE: DEPTH-AWARE UPSCALE OF THE MARCH (OR THE RESOLVE), BLENDED ONTO THE MAIN
 *   COLOR TARGET WITH PREMULTIPLIED-ALPHA BLENDING. ONE QUAD AROUND EVERY PUFF.</li>
 *   <li>CAPTURE (TEMPORAL SMOOTHING ONLY): KEEPS THIS FRAME'S PROJECTION MATRIX FOR THE
 *   NEXT FRAME'S RESOLVE.</li>
 * </ol>
 *
 * <p>AT FULL RESOLUTION WITHOUT TEMPORAL SMOOTHING THE UPSCALE WOULD ONLY COPY PIXELS, SO A
 * SINGLE PASS MARCHES STRAIGHT ONTO THE MAIN COLOR TARGET WITH PREMULTIPLIED-ALPHA BLENDING
 * INSTEAD.
 *
 * <p>DEPTH IS NEVER ATTACHED, SO THERE IS NO READ/WRITE FEEDBACK LOOP.
 */
public final class FartGasRenderer {

    private static final Logger LOGGER = ButtCraft.logger("gas");

    // TILEABLE NOISE VOLUMES STORED AS 2D ATLASES (LAYOUTS DESCRIBED IN fart_gas.fsh):
    // THE DOMAIN WARP (32^3) AND THE SHAPE / DETAIL EROSION (64^3), EACH WITH TWO
    // NEIGHBOURING SLICES PRE-BAKED INTO EVERY TEXEL SO A LOOKUP IS ONE FETCH.
    private static final Identifier WARP_TEXTURE = ButtCraft.id("textures/fart_gas_warp.png");
    private static final Identifier SHAPE_TEXTURE = ButtCraft.id("textures/fart_gas_shape.png");

    // THE MARCH INTO THE CLEARED REDUCED-RESOLUTION TARGETS (NO BLENDING)...
    private static final RenderPipeline PIPELINE = RenderPipelines.registerOptional(
        marchPipeline("pipeline/fart_gas")
            .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT, ColorTargetState.WRITE_ALL))
            .build());

    // ...OR BLENDED STRAIGHT ONTO THE SCENE (FULL RESOLUTION WITHOUT TEMPORAL SMOOTHING ONLY).
    private static final RenderPipeline DIRECT_PIPELINE = RenderPipelines.registerOptional(
        marchPipeline("pipeline/fart_gas_direct")
            .withShaderDefine("FART_GAS_DIRECT")
            .withShaderDefine("FART_GAS_SINGLE_RECT")
            .withColorTargetState(new ColorTargetState(
                Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
                GpuFormat.RGBA8_UNORM,
                ColorTargetState.WRITE_ALL))
            .build());

    private static final RenderPipeline RESOLVE_PIPELINE = RenderPipelines.registerOptional(
        screenPipeline("pipeline/fart_gas_resolve", "core/fart_gas_resolve")
            .withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("FartGasInfo", UniformType.UNIFORM_BUFFER)
                .withUniform("GasSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("GasDistanceSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("HistorySampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("PreviousProjectionSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .build())
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
            .build());

    private static final RenderPipeline COMPOSITE_PIPELINE = RenderPipelines.registerOptional(
        screenPipeline("pipeline/fart_gas_composite", "core/fart_gas_composite")
            .withShaderDefine("FART_GAS_SINGLE_RECT")
            .withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("FartGasInfo", UniformType.UNIFORM_BUFFER)
                .withUniform("GasSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("GasDistanceSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .build())
            .withColorTargetState(new ColorTargetState(
                Optional.of(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA),
                GpuFormat.RGBA8_UNORM,
                ColorTargetState.WRITE_ALL))
            .build());

    private static final RenderPipeline CAPTURE_PIPELINE = RenderPipelines.registerOptional(
        RenderPipeline.builder()
            .withLocation(ButtCraft.id("pipeline/fart_gas_capture"))
            .withVertexShader(ButtCraft.id("core/fart_gas_capture"))
            .withFragmentShader(ButtCraft.id("core/fart_gas_capture"))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build());

    private static final Optional<Vector4fc> CLEAR_TRANSPARENT = Optional.of(new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));

    // HOW MUCH OF LAST FRAME'S RESULT EACH FRAME KEEPS (ROUGHLY A 10-FRAME AVERAGE).
    private static final float HISTORY_WEIGHT = 0.9F;
    // A CAMERA THAT MOVED FARTHER THAN THIS (BLOCKS) IN ONE FRAME TELEPORTED: DROP THE HISTORY.
    private static final double MAX_HISTORY_MOTION = 8.0;
    // THE MARCH'S JITTER REPEATS AFTER THIS MANY FRAMES (A POWER OF TWO).
    private static final int JITTER_FRAMES = 64;

    private final FartGasSimulation simulation;
    private @Nullable MappableRingBuffer ubo;
    private @Nullable TextureTarget gasTarget;
    private @Nullable TextureTarget distanceTarget;
    private final @Nullable TextureTarget[] historyTargets = new TextureTarget[2];
    private @Nullable TextureTarget projectionTarget;

    // TEMPORAL SMOOTHING: WHICH HISTORY TARGET THIS FRAME WRITES, WHETHER THE OTHER ONE HOLDS
    // LAST FRAME'S RESULT, AND THE CAMERA IT WAS SEEN FROM.
    private int historyIndex;
    private boolean historyValid;
    private int frame;
    private Vec3 previousCameraPos = Vec3.ZERO;
    private final Matrix4f previousViewRotation = new Matrix4f();

    private boolean warnedMissingPipeline;
    private boolean warnedMissingTarget;

    public FartGasRenderer(FartGasSimulation simulation) {
        this.simulation = simulation;
    }

    public void render(LevelRenderContext context) {
        // HISTORY ONLY CARRIES OVER BETWEEN CONSECUTIVE FRAMES THAT BOTH DREW GAS.
        boolean hadHistory = this.historyValid;
        this.historyValid = false;

        List<FartGasSimulation.Puff> puffs = this.simulation.puffs();
        if (puffs.isEmpty()) {
            return;
        }

        // READ ONCE SO THE UNIFORMS AND THE TARGET SIZES AGREE EVEN IF THE SETTING CHANGES MID-FRAME.
        FartGasSettings.Values quality = FartGasSettings.current();
        boolean temporal = quality.temporalSmoothing();
        // AT FULL RESOLUTION WITHOUT SMOOTHING THE MARCH BLENDS STRAIGHT ONTO THE SCENE AND NEEDS NO TARGETS.
        boolean direct = quality.resolutionDivisor() == 1 && !temporal;

        CompiledRenderPipeline marchPipeline = this.compiled(direct ? DIRECT_PIPELINE : PIPELINE);
        CompiledRenderPipeline resolvePipeline = temporal ? this.compiled(RESOLVE_PIPELINE) : null;
        CompiledRenderPipeline compositePipeline = direct ? null : this.compiled(COMPOSITE_PIPELINE);
        CompiledRenderPipeline capturePipeline = temporal ? this.compiled(CAPTURE_PIPELINE) : null;
        if (marchPipeline == null || (!direct && compositePipeline == null) || (temporal && (resolvePipeline == null || capturePipeline == null))) {
            return;
        }

        TextureManager textures = Minecraft.getInstance().getTextureManager();
        GpuTextureView warp = textures.getTexture(WARP_TEXTURE).getTextureView();
        GpuTextureView shape = textures.getTexture(SHAPE_TEXTURE).getTextureView();

        // THE PIPELINES READ THE LEVEL PROJECTION AND FOG BUFFERS SET UP BY VANILLA.
        if (RenderSystem.getProjectionMatrixBuffer() == null || RenderSystem.getShaderFog() == null) {
            return;
        }

        RenderTarget mainTarget = context.gameRenderer().mainRenderTarget();
        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorView == null || depthView == null) {
            // MOST LIKELY A SHADER MOD REPLACING THE MAIN TARGET. WITHOUT THIS THE GAS JUST VANISHES.
            if (!this.warnedMissingTarget) {
                this.warnedMissingTarget = true;
                LOGGER.warn("MAIN TARGET HAS NO {} VIEW, GAS WILL NOT RENDER.", colorView == null ? "COLOR" : "DEPTH");
            }
            return;
        }

        if (this.ubo == null) {
            this.ubo = new MappableRingBuffer(() -> "ButtCraft fart gas UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FartGasUniforms.SIZE);
        }

        LevelRenderState levelState = context.levelState();
        FartGasUniforms.Lighting lighting = FartGasUniforms.lighting(levelState.skyRenderState);
        // THE SIMULATION WORKS OUT EACH PUFF'S OPTICAL DEPTH TOWARD THE SAME LIGHT.
        this.simulation.setLight(lighting.direction(), lighting.strength() > 0.0F);

        int divisor = quality.resolutionDivisor();
        int width = Math.max((mainTarget.width + divisor - 1) / divisor, 1);
        int height = Math.max((mainTarget.height + divisor - 1) / divisor, 1);

        CameraRenderState camera = levelState.cameraRenderState;
        FartGasUniforms.Temporal frameTemporal = FartGasUniforms.Temporal.NONE;
        if (temporal) {
            this.frame = (this.frame + 1) & (JITTER_FRAMES - 1);
            Vec3 motion = camera.pos.subtract(this.previousCameraPos);
            TextureTarget previous = this.historyTargets[1 - this.historyIndex];
            boolean usable = hadHistory
                && motion.lengthSqr() < MAX_HISTORY_MOTION * MAX_HISTORY_MOTION
                && previous != null && previous.width == width && previous.height == height;
            frameTemporal = new FartGasUniforms.Temporal(
                this.frame,
                usable ? HISTORY_WEIGHT : 0.0F,
                new Vector3f((float) motion.x, (float) motion.y, (float) motion.z),
                this.previousViewRotation);
        }

        GpuBuffer buffer = this.ubo.currentBuffer();
        FartGasUniforms.Upload upload;
        try (GpuBufferSlice.MappedView view = buffer.slice().map(false, true)) {
            upload = FartGasUniforms.write(view.data(), levelState, puffs, quality, lighting, frameTemporal);
        }

        // EVERY PUFF IS TOO FAINT TO SEE (E.G. THE TAIL END OF A FADING CLOUD).
        if (upload.puffs() == 0) {
            return;
        }

        GpuTextureView gasView = null;
        GpuTextureView distanceView = null;
        GpuTextureView historyView = null;
        GpuTextureView previousHistoryView = null;
        GpuTextureView projectionView = null;
        if (direct) {
            this.destroyTargets();
        } else {
            this.gasTarget = sized(this.gasTarget, "ButtCraft fart gas", width, height, GpuFormat.RGBA8_UNORM);
            this.distanceTarget = sized(this.distanceTarget, "ButtCraft fart gas distance", width, height, GpuFormat.RG32_FLOAT);
            gasView = this.gasTarget.getColorTextureView();
            distanceView = this.distanceTarget.getColorTextureView();
            if (gasView == null || distanceView == null) {
                return;
            }

            if (temporal) {
                for (int historySlot = 0; historySlot < 2; historySlot++) {
                    this.historyTargets[historySlot] = sized(this.historyTargets[historySlot], "ButtCraft fart gas history " + historySlot, width, height, GpuFormat.RGBA16_FLOAT);
                }
                this.projectionTarget = sized(this.projectionTarget, "ButtCraft fart gas projection", 4, 1, GpuFormat.RGBA32_FLOAT);
                historyView = this.historyTargets[this.historyIndex].getColorTextureView();
                previousHistoryView = this.historyTargets[1 - this.historyIndex].getColorTextureView();
                projectionView = this.projectionTarget.getColorTextureView();
                if (historyView == null || previousHistoryView == null || projectionView == null) {
                    return;
                }
            } else {
                this.destroyHistory();
            }
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        // ONE QUAD (6 VERTICES) PER SCREEN RECTANGLE IN THE PASSES THAT DON'T BLEND.
        int rectVertices = 6 * Math.max(upload.rects(), 1);

        // 1. MARCH. AT REDUCED RESOLUTION THE CLEAR MATTERS: THE UPSCALE READS TEXELS JUST
        // OUTSIDE THE QUADS, WHICH MUST BE EMPTY RATHER THAN LAST FRAME'S GAS.
        try (RenderPass pass = direct
                ? encoder.createRenderPass(() -> "ButtCraft fart gas march (direct)", colorView, Optional.empty())
                : encoder.createRenderPass(RenderPassDescriptor.builder(() -> "ButtCraft fart gas march")
                    .withColorAttachment(gasView, CLEAR_TRANSPARENT)
                    .withColorAttachment(distanceView, CLEAR_TRANSPARENT)
                    .build())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(marchPipeline);
            pass.setUniform("FartGasInfo", buffer);
            pass.setUniform("WarpSampler", warp, linear);
            pass.setUniform("ShapeSampler", shape, linear);
            pass.setUniform("DepthSampler", depthView, nearest);
            pass.draw(direct ? 6 : rectVertices, 1, 0, 0);
        }

        if (direct) {
            this.ubo.rotate();
            return;
        }

        // 2. TEMPORAL SMOOTHING INTO THIS FRAME'S HISTORY TARGET (CLEARED FOR THE SAME REASON).
        GpuTextureView compositeSource = gasView;
        if (temporal) {
            try (RenderPass pass = encoder.createRenderPass(() -> "ButtCraft fart gas resolve", historyView, CLEAR_TRANSPARENT)) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setPipeline(resolvePipeline);
                pass.setUniform("FartGasInfo", buffer);
                pass.setUniform("GasSampler", gasView, nearest);
                pass.setUniform("GasDistanceSampler", distanceView, nearest);
                pass.setUniform("HistorySampler", previousHistoryView, linear);
                pass.setUniform("PreviousProjectionSampler", projectionView, nearest);
                pass.draw(rectVertices, 1, 0, 0);
            }

            compositeSource = historyView;
        }

        // 3. DEPTH-AWARE UPSCALE ONTO THE SCENE.
        try (RenderPass pass = encoder.createRenderPass(() -> "ButtCraft fart gas composite", colorView, Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(compositePipeline);
            pass.setUniform("FartGasInfo", buffer);
            pass.setUniform("GasSampler", compositeSource, nearest);
            pass.setUniform("GasDistanceSampler", distanceView, nearest);
            pass.setUniform("DepthSampler", depthView, nearest);
            pass.draw(6, 1, 0, 0);
        }

        // 4. KEEP THIS FRAME'S CAMERA FOR THE NEXT FRAME'S RESOLVE. THE PROJECTION IS READ BACK
        // AFTER THE RESOLVE ABOVE HAS USED LAST FRAME'S.
        if (temporal) {
            try (RenderPass pass = encoder.createRenderPass(() -> "ButtCraft fart gas projection capture", projectionView, Optional.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                pass.setPipeline(capturePipeline);
                pass.draw(3, 1, 0, 0);
            }

            this.previousCameraPos = camera.pos;
            this.previousViewRotation.set(camera.viewRotationMatrix);
            this.historyIndex = 1 - this.historyIndex;
            this.historyValid = true;
        }

        this.ubo.rotate();
    }

    private @Nullable CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
        if (compiled == null && !this.warnedMissingPipeline) {
            this.warnedMissingPipeline = true;
            // VANILLA ALREADY LOGGED THE COMPILE ERROR ITSELF WHEN THE RESOURCES LOADED.
            LOGGER.warn("PIPELINE {} UNAVAILABLE, GAS WILL NOT RENDER.", pipeline.getLocation());
        }

        return compiled;
    }

    /** A PIPELINE DRAWING fart_gas.vsh's SCREEN QUADS (NO VERTEX BUFFER). */
    private static RenderPipeline.Builder screenPipeline(String location, String fragmentShader) {
        return RenderPipeline.builder()
            .withLocation(ButtCraft.id(location))
            .withVertexShader(ButtCraft.id("core/fart_gas"))
            .withFragmentShader(ButtCraft.id(fragmentShader))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false);
    }

    /** THE RAY-MARCH PIPELINE, WITHOUT ITS COLOR TARGETS. */
    private static RenderPipeline.Builder marchPipeline(String location) {
        return screenPipeline(location, "core/fart_gas")
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("FartGasInfo", UniformType.UNIFORM_BUFFER)
                .withUniform("WarpSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("ShapeSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("DepthSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                .build());
    }

    public void close() {
        if (this.ubo != null) {
            this.ubo.close();
            this.ubo = null;
        }

        this.destroyTargets();
    }

    private void destroyTargets() {
        this.gasTarget = destroy(this.gasTarget);
        this.distanceTarget = destroy(this.distanceTarget);
        this.destroyHistory();
    }

    private void destroyHistory() {
        for (int historySlot = 0; historySlot < 2; historySlot++) {
            this.historyTargets[historySlot] = destroy(this.historyTargets[historySlot]);
        }

        this.projectionTarget = destroy(this.projectionTarget);
        this.historyValid = false;
    }

    private static @Nullable TextureTarget destroy(@Nullable TextureTarget target) {
        if (target != null) {
            target.destroyBuffers();
        }

        return null;
    }

    /** target, (RE)CREATED OR RESIZED TO width x height. */
    private static TextureTarget sized(@Nullable TextureTarget target, String label, int width, int height, GpuFormat format) {
        if (target == null) {
            return new TextureTarget(label, width, height, format, null);
        }

        if (target.width != width || target.height != height) {
            target.resize(width, height);
        }

        return target;
    }
}
