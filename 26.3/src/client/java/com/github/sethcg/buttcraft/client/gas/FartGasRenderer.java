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

// THE ONLY VERSION-SPECIFIC CLASS: MOJANG'S GPU API CHANGES BETWEEN RELEASES,
// SO EACH MINECRAFT VERSION HAS ITS OWN COPY
//
// RUNS AT END_MAIN, AFTER EVERY MAIN RENDER PASS HAS CLOSED, WHICH WORKS WITH BOTH
// TRANSPARENCY MODES. WITH DEPTH NEVER ATTACHED, THERE IS NO READ/WRITE FEEDBACK LOOP
//
// 1. MARCH INTO CLEARED REDUCED-RESOLUTION TARGETS, ONE QUAD PER SEPARATE CLOUD
// 2. RESOLVE: BLEND WITH LAST FRAME'S REPROJECTED RESULT
// 3. COMPOSITE: DEPTH-AWARE UPSCALE ONTO THE SCENE, ONE QUAD AROUND EVERY PUFF
// 4. CAPTURE: KEEP THE PROJECTION MATRIX FOR THE NEXT RESOLVE
public final class FartGasRenderer {

    private static final Logger LOGGER = ButtCraft.logger("gas");

    // TILEABLE NOISE VOLUMES STORED AS 2D ATLASES (LAYOUTS DESCRIBED IN fart_gas.fsh)
    private static final Identifier WARP_TEXTURE = ButtCraft.id("textures/fart_gas_warp.png");
    private static final Identifier SHAPE_TEXTURE = ButtCraft.id("textures/fart_gas_shape.png");

    private static final RenderPipeline PIPELINE = RenderPipelines.registerOptional(
        marchPipeline("pipeline/fart_gas")
            .withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT, ColorTargetState.WRITE_ALL))
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

    // ROUGHLY A 10-FRAME AVERAGE
    private static final float HISTORY_WEIGHT = 0.9F;
    // A CAMERA THAT MOVED FARTHER THAN THIS (BLOCKS) IN ONE FRAME TELEPORTED: DROP THE HISTORY
    private static final double MAX_HISTORY_MOTION = 8.0;
    // A POWER OF TWO (USED AS A MASK)
    private static final int JITTER_FRAMES = 64;

    private final FartGasSimulation simulation;
    private @Nullable MappableRingBuffer ubo;
    private @Nullable TextureTarget gasTarget;
    private @Nullable TextureTarget distanceTarget;
    private final @Nullable TextureTarget[] historyTargets = new TextureTarget[2];
    private @Nullable TextureTarget projectionTarget;

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
        // HISTORY ONLY CARRIES OVER BETWEEN CONSECUTIVE FRAMES THAT BOTH DREW GAS
        boolean hadHistory = this.historyValid;
        this.historyValid = false;

        List<FartGasSimulation.Puff> puffs = this.simulation.puffs();
        if (puffs.isEmpty()) {
            return;
        }

        CompiledRenderPipeline marchPipeline = this.compiled(PIPELINE);
        CompiledRenderPipeline resolvePipeline = this.compiled(RESOLVE_PIPELINE);
        CompiledRenderPipeline compositePipeline = this.compiled(COMPOSITE_PIPELINE);
        CompiledRenderPipeline capturePipeline = this.compiled(CAPTURE_PIPELINE);
        if (marchPipeline == null || resolvePipeline == null || compositePipeline == null || capturePipeline == null) {
            return;
        }

        TextureManager textures = Minecraft.getInstance().getTextureManager();
        GpuTextureView warp = textures.getTexture(WARP_TEXTURE).getTextureView();
        GpuTextureView shape = textures.getTexture(SHAPE_TEXTURE).getTextureView();

        if (RenderSystem.getProjectionMatrixBuffer() == null || RenderSystem.getShaderFog() == null) {
            return;
        }

        RenderTarget mainTarget = context.gameRenderer().mainRenderTarget();
        GpuTextureView colorView = mainTarget.getColorTextureView();
        GpuTextureView depthView = mainTarget.getDepthTextureView();
        if (colorView == null || depthView == null) {
            // MOST LIKELY A SHADER MOD REPLACING THE MAIN TARGET
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
        this.simulation.setLight(lighting.direction(), lighting.strength() > 0.0F);

        int divisor = FartGasUniforms.RESOLUTION_DIVISOR;
        int width = Math.max((mainTarget.width + divisor - 1) / divisor, 1);
        int height = Math.max((mainTarget.height + divisor - 1) / divisor, 1);

        CameraRenderState camera = levelState.cameraRenderState;
        this.frame = (this.frame + 1) & (JITTER_FRAMES - 1);
        Vec3 motion = camera.pos.subtract(this.previousCameraPos);
        TextureTarget previous = this.historyTargets[1 - this.historyIndex];
        boolean usable = hadHistory
            && motion.lengthSqr() < MAX_HISTORY_MOTION * MAX_HISTORY_MOTION
            && previous != null && previous.width == width && previous.height == height;
        FartGasUniforms.Temporal frameTemporal = new FartGasUniforms.Temporal(
            this.frame,
            usable ? HISTORY_WEIGHT : 0.0F,
            new Vector3f((float) motion.x, (float) motion.y, (float) motion.z),
            this.previousViewRotation);

        GpuBuffer buffer = this.ubo.currentBuffer();
        FartGasUniforms.Upload upload;
        try (GpuBufferSlice.MappedView view = buffer.slice().map(false, true)) {
            upload = FartGasUniforms.write(view.data(), levelState, puffs, lighting, frameTemporal);
        }

        if (upload.puffs() == 0) {
            return;
        }

        this.gasTarget = sized(this.gasTarget, "ButtCraft fart gas", width, height, GpuFormat.RGBA8_UNORM);
        this.distanceTarget = sized(this.distanceTarget, "ButtCraft fart gas distance", width, height, GpuFormat.RG32_FLOAT);
        for (int historySlot = 0; historySlot < 2; historySlot++) {
            this.historyTargets[historySlot] = sized(this.historyTargets[historySlot], "ButtCraft fart gas history " + historySlot, width, height, GpuFormat.RGBA16_FLOAT);
        }
        this.projectionTarget = sized(this.projectionTarget, "ButtCraft fart gas projection", 4, 1, GpuFormat.RGBA32_FLOAT);

        GpuTextureView gasView = this.gasTarget.getColorTextureView();
        GpuTextureView distanceView = this.distanceTarget.getColorTextureView();
        GpuTextureView historyView = this.historyTargets[this.historyIndex].getColorTextureView();
        GpuTextureView previousHistoryView = this.historyTargets[1 - this.historyIndex].getColorTextureView();
        GpuTextureView projectionView = this.projectionTarget.getColorTextureView();
        if (gasView == null || distanceView == null || historyView == null || previousHistoryView == null || projectionView == null) {
            return;
        }

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        int rectVertices = 6 * Math.max(upload.rects(), 1);

        // CLEARED BECAUSE THE UPSCALE READS TEXELS JUST OUTSIDE THE QUADS, AND THOSE CAN'T STILL HOLD
        // LAST FRAME'S GAS
        try (RenderPass pass = encoder.createRenderPass(RenderPassDescriptor.builder(() -> "ButtCraft fart gas march")
                .withColorAttachment(gasView, CLEAR_TRANSPARENT)
                .withColorAttachment(distanceView, CLEAR_TRANSPARENT)
                .build())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(marchPipeline);
            pass.setUniform("FartGasInfo", buffer);
            pass.setUniform("WarpSampler", warp, linear);
            pass.setUniform("ShapeSampler", shape, linear);
            pass.setUniform("DepthSampler", depthView, nearest);
            pass.draw(rectVertices, 1, 0, 0);
        }

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

        try (RenderPass pass = encoder.createRenderPass(() -> "ButtCraft fart gas composite", colorView, Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(compositePipeline);
            pass.setUniform("FartGasInfo", buffer);
            pass.setUniform("GasSampler", historyView, nearest);
            pass.setUniform("GasDistanceSampler", distanceView, nearest);
            pass.setUniform("DepthSampler", depthView, nearest);
            pass.draw(6, 1, 0, 0);
        }

        // CAPTURED AFTER THE RESOLVE ABOVE HAS USED LAST FRAME'S PROJECTION
        try (RenderPass pass = encoder.createRenderPass(() -> "ButtCraft fart gas projection capture", projectionView, Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(capturePipeline);
            pass.draw(3, 1, 0, 0);
        }

        this.previousCameraPos = camera.pos;
        this.previousViewRotation.set(camera.viewRotationMatrix);
        this.historyIndex = 1 - this.historyIndex;
        this.historyValid = true;

        this.ubo.rotate();
    }

    private @Nullable CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(pipeline);
        if (compiled == null && !this.warnedMissingPipeline) {
            this.warnedMissingPipeline = true;
            // VANILLA ALREADY LOGGED THE COMPILE ERROR ITSELF WHEN THE RESOURCES LOADED
            LOGGER.warn("PIPELINE {} UNAVAILABLE, GAS WILL NOT RENDER.", pipeline.getLocation());
        }

        return compiled;
    }

    private static RenderPipeline.Builder screenPipeline(String location, String fragmentShader) {
        return RenderPipeline.builder()
            .withLocation(ButtCraft.id(location))
            .withVertexShader(ButtCraft.id("core/fart_gas"))
            .withFragmentShader(ButtCraft.id(fragmentShader))
            .withBindGroupLayout(BindGroupLayouts.PROJECTION)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false);
    }

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

        this.gasTarget = destroy(this.gasTarget);
        this.distanceTarget = destroy(this.distanceTarget);
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
