package com.github.sethcg.buttcraft.client.gas;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

// THE LAYOUT MUST MATCH FartGasInfo (STD140) IN shaders/include/fart_gas_common.glsl
public final class FartGasUniforms {

    public static final int RESOLUTION_DIVISOR = 4;
    // fart_gas.fsh'S LOOP BOUNDS MUST STAY AT LEAST THESE
    private static final int VIEW_STEPS = 20;
    private static final int SHADOW_STEPS = 2;

    // THE LCM OF THE SHADER'S NOISE PERIODS (4, 1.5, 0.5 BLOCKS)
    private static final double NOISE_PERIOD = 12.0;
    // EVERY NOISE SCROLL SPEED IN fart_gas.fsh TIMES THIS IS A WHOLE NUMBER, SO THE NOISE
    // DOESN'T JUMP WHEN TIME WRAPS
    private static final double TIME_PERIOD_SECONDS = 2000.0;

    private static final Vector3fc GAS_ALBEDO = new Vector3f(0.48F, 0.63F, 0.31F);
    private static final float GAS_DENSITY = 1.6F;

    private static final float MIN_VISIBLE_DENSITY = 0.002F;

    // 16-BIT FIXED POINT WALL OFFSETS FROM THE PUFF'S CENTER. A SIDE WITH NO WALL NEARBY GOES AT THE
    // END OF THE RANGE. MUST MATCH WALL_RANGE / WALL_STEP IN fart_gas_common.glsl
    private static final float WALL_RANGE = 8.0F;
    private static final float WALL_STEPS_PER_BLOCK = 4096.0F;

    private static final float IMMERSION_START = 0.05F;
    private static final float IMMERSION_FULL = 0.4F;

    // SORTING SCRATCH (RENDER THREAD ONLY)
    private static final long[] SORT_KEYS = new long[FartGasSimulation.MAX_PUFFS];
    private static final FartGasSimulation.Puff[] SORTED = new FartGasSimulation.Puff[FartGasSimulation.MAX_PUFFS];
    private static final float[] SORTED_DENSITY = new float[FartGasSimulation.MAX_PUFFS];
    private static final FartGasSimulation.Puff[] GROUPED = new FartGasSimulation.Puff[FartGasSimulation.MAX_PUFFS];
    private static final float[] GROUPED_DENSITY = new float[FartGasSimulation.MAX_PUFFS];
    private static final int[] RECT_ORDER = new int[FartGasSimulation.MAX_RECTS];
    private static final int[] RECT_ENDS = new int[FartGasSimulation.MAX_RECTS];

    public static final int SIZE;

    static {
        Std140SizeCalculator size = new Std140SizeCalculator()
            .putMat4f()
            .putMat4f()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putVec4()
            .putIVec4()
            .putIVec4();
        for (int runGroup = 0; runGroup < FartGasSimulation.MAX_RECTS / 4; runGroup++) {
            size.putIVec4();
        }
        for (int puffIndex = 0; puffIndex < FartGasSimulation.MAX_PUFFS; puffIndex++) {
            size.putVec4().putVec4().putIVec4();
        }
        SIZE = size.get();
    }

    public record Lighting(Vector3fc direction, float strength, Vector3fc color, Vector3fc ambient) {
    }

    // frame VARIES THE MARCH'S JITTER; historyWeight 0 MEANS THERE IS NO USABLE HISTORY
    public record Temporal(int frame, float historyWeight, Vector3fc cameraMotion, Matrix4fc previousWorldToView) {
    }

    public record Upload(int puffs, int rects) {
    }

    private FartGasUniforms() {
    }

    public static Upload write(
        ByteBuffer data,
        LevelRenderState levelState,
        List<FartGasSimulation.Puff> puffs,
        Lighting lighting,
        Temporal temporal
    ) {
        CameraRenderState camera = levelState.cameraRenderState;
        float partial = levelState.worldPartialTicks;
        Vec3 cameraPos = camera.pos;

        Matrix4f viewToWorld = new Matrix4f(camera.viewRotationMatrix).invert();
        double time = ((levelState.gameTime % (long) (TIME_PERIOD_SECONDS * 20.0)) + partial) / 20.0;

        Std140Builder builder = Std140Builder.intoBuffer(data)
            .putMat4f(viewToWorld)
            .putMat4f(temporal.previousWorldToView())
            .putVec4(
                (float) Mth.positiveModulo(cameraPos.x, NOISE_PERIOD),
                (float) Mth.positiveModulo(cameraPos.y, NOISE_PERIOD),
                (float) Mth.positiveModulo(cameraPos.z, NOISE_PERIOD),
                (float) time);

        Vector3fc lightDir = lighting.direction();
        builder.putVec4(lightDir.x(), lightDir.y(), lightDir.z(), lighting.strength());
        int count = sortVisible(puffs, partial);
        int rects = groupByRect(count);

        builder.putVec4(lighting.color().x(), lighting.color().y(), lighting.color().z(), 0.0F);
        builder.putVec4(lighting.ambient().x(), lighting.ambient().y(), lighting.ambient().z(), immersion(count, cameraPos, partial));
        builder.putVec4(GAS_ALBEDO.x(), GAS_ALBEDO.y(), GAS_ALBEDO.z(), GAS_DENSITY);

        Vector3fc motion = temporal.cameraMotion();
        builder.putVec4(motion.x(), motion.y(), motion.z(), temporal.historyWeight());

        builder.putIVec4(count, VIEW_STEPS, SHADOW_STEPS, RESOLUTION_DIVISOR);
        builder.putIVec4(temporal.frame(), rects, 0, 0);
        for (int run = 0; run < FartGasSimulation.MAX_RECTS; run += 4) {
            builder.putIVec4(RECT_ENDS[run], RECT_ENDS[run + 1], RECT_ENDS[run + 2], RECT_ENDS[run + 3]);
        }

        // THE SHADERS NEVER READ PAST THE COUNT, SO THE REST OF THE ARRAY CAN STAY STALE
        for (int puffIndex = 0; puffIndex < count; puffIndex++) {
            FartGasSimulation.Puff puff = GROUPED[puffIndex];
            float radius = puff.radius(partial);
            double puffX = puff.x(partial);
            double puffY = puff.y(partial);
            double puffZ = puff.z(partial);
            builder.putVec4((float) (puffX - cameraPos.x), (float) (puffY - cameraPos.y), (float) (puffZ - cameraPos.z), radius);
            builder.putVec4(GROUPED_DENSITY[puffIndex], 1.0F / (radius * radius), puff.sunDepth, puff.skyDepth);
            builder.putIVec4(
                pack(unorm16(puff.skyLight), unorm16(puff.blockLight)),
                pack(wall(puff.wallMinX, puffX, -WALL_RANGE), wall(puff.wallMinY, puffY, -WALL_RANGE)),
                pack(wall(puff.wallMinZ, puffZ, -WALL_RANGE), wall(puff.wallMaxX, puffX, WALL_RANGE)),
                pack(wall(puff.wallMaxY, puffY, WALL_RANGE), wall(puff.wallMaxZ, puffZ, WALL_RANGE)));
            SORTED[puffIndex] = null;
            GROUPED[puffIndex] = null;
        }

        return new Upload(count, rects);
    }

    // HOW FAR INSIDE THE GAS THE CAMERA IS (0-1). FROM INSIDE, THE GAS FILLS THE SCREEN, SO
    // fart_gas.fsh SKIPS ITS SHADOW MARCH AND ONLY MARCHES THE NEAREST GAS
    private static float immersion(int count, Vec3 cameraPos, float partial) {
        float weightSum = 0.0F;
        for (int puffIndex = 0; puffIndex < count; puffIndex++) {
            FartGasSimulation.Puff puff = GROUPED[puffIndex];
            float radius = puff.radius(partial);
            double offsetX = puff.x(partial) - cameraPos.x;
            double offsetY = puff.y(partial) - cameraPos.y;
            double offsetZ = puff.z(partial) - cameraPos.z;
            float distanceSquared = (float) ((offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ) / (radius * radius));
            if (distanceSquared < 1.0F) {
                weightSum += (1.0F - distanceSquared) * (1.0F - distanceSquared);
            }
        }

        return smoothstep(IMMERSION_START, IMMERSION_FULL, Math.min(weightSum * FartGasSimulation.FIELD_COVERAGE_SCALE, 1.0F));
    }

    private static int wall(double coordinate, double center, float noWall) {
        double offset = Double.isFinite(coordinate) ? coordinate - center : noWall;
        return (int) Mth.clamp(Math.round((offset + WALL_RANGE) * WALL_STEPS_PER_BLOCK), 0L, 0xFFFFL);
    }

    private static int unorm16(float value) {
        return Math.round(Mth.clamp(value, 0.0F, 1.0F) * 0xFFFF);
    }

    private static int pack(int low, int high) {
        return low | high << 16;
    }

    // DENSEST FIRST, BECAUSE THE SHADER STOPS COLLECTING SHADOW-ONLY PUFFS ONCE ITS HIT LIST IS FULL
    // (ON THE VIEW RAY IT KEEPS THE PUFFS THAT ADD THE MOST GAS, WHATEVER THEIR ORDER.)
    private static int sortVisible(List<FartGasSimulation.Puff> puffs, float partial) {
        int count = 0;
        int puffCount = Math.min(puffs.size(), SORT_KEYS.length);
        for (int puffIndex = 0; puffIndex < puffCount; puffIndex++) {
            float density = puffs.get(puffIndex).density(partial);
            if (density < MIN_VISIBLE_DENSITY) {
                continue;
            }

            // POSITIVE FLOATS SORT THE SAME AS THEIR RAW BITS, SO THESE KEYS SORT BY DENSITY
            SORT_KEYS[count++] = (long) Float.floatToRawIntBits(density) << 32 | puffIndex;
        }

        Arrays.sort(SORT_KEYS, 0, count);
        for (int slot = 0; slot < count; slot++) {
            long key = SORT_KEYS[count - 1 - slot];
            SORTED[slot] = puffs.get((int) key);
            SORTED_DENSITY[slot] = Float.intBitsToFloat((int) (key >>> 32));
        }

        return count;
    }

    // ONE RUN PER SCREEN RECTANGLE. THE RUNS GO IN ORDER OF THEIR DENSEST PUFF AND STAY DENSEST FIRST
    // INSIDE, SO A FULL HIT LIST STILL MOSTLY DROPS THE FAINTEST PUFFS
    private static int groupByRect(int count) {
        Arrays.fill(RECT_ORDER, -1);
        int rects = 0;
        for (int puffIndex = 0; puffIndex < count; puffIndex++) {
            int rect = rectOf(SORTED[puffIndex]);
            if (RECT_ORDER[rect] < 0) {
                RECT_ORDER[rect] = rects++;
            }
        }

        Arrays.fill(RECT_ENDS, 0);
        for (int puffIndex = 0; puffIndex < count; puffIndex++) {
            RECT_ENDS[RECT_ORDER[rectOf(SORTED[puffIndex])]]++;
        }

        for (int run = 1; run < FartGasSimulation.MAX_RECTS; run++) {
            RECT_ENDS[run] += RECT_ENDS[run - 1];
        }

        // FILLING BACKWARD KEEPS EACH RUN DENSEST FIRST. RECT_ENDS ENDS UP HOLDING EACH RUN'S START...
        for (int puffIndex = count - 1; puffIndex >= 0; puffIndex--) {
            int run = RECT_ORDER[rectOf(SORTED[puffIndex])];
            int slot = --RECT_ENDS[run];
            GROUPED[slot] = SORTED[puffIndex];
            GROUPED_DENSITY[slot] = SORTED_DENSITY[puffIndex];
        }

        // ...SO SHIFT IT BACK TO THE ENDS. EVERY UNUSED RECTANGLE ENDS WHERE THE LAST ONE DOES
        for (int run = 0; run < FartGasSimulation.MAX_RECTS; run++) {
            RECT_ENDS[run] = run + 1 < rects ? RECT_ENDS[run + 1] : count;
        }

        return rects;
    }

    private static int rectOf(FartGasSimulation.Puff puff) {
        return Mth.clamp(puff.rect, 0, FartGasSimulation.MAX_RECTS - 1);
    }

    public static Lighting lighting(SkyRenderState sky) {
        if (sky.skybox != DimensionType.Skybox.OVERWORLD) {
            // FLAT, DIMENSION-TINTED AMBIENT (PUFF SKY LIGHT IS FORCED TO 1 IN SKYLESS DIMENSIONS)
            boolean end = sky.skybox == DimensionType.Skybox.END;
            return new Lighting(
                new Vector3f(0.0F, 1.0F, 0.0F),
                0.0F,
                new Vector3f(0.0F, 0.0F, 0.0F),
                new Vector3f(end ? 0.30F : 0.46F, end ? 0.26F : 0.34F, end ? 0.38F : 0.27F));
        }

        float sunAngle = sky.sunAngle;
        Vector3f sunDir = new Vector3f(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0.0F);
        float sunHeight = sunDir.y;
        float clearSky = sky.rainBrightness;

        // SAME SHAPE AS VANILLA'S SKY DARKENING, SO THE GAS DIMS IN STEP WITH THE TERRAIN
        float daylight = Mth.clamp(sunHeight * 2.0F + 0.2F, 0.0F, 1.0F);

        Vector3f lightDir;
        Vector3f lightColor;
        float strength;
        if (sunHeight > -0.08F) {
            lightDir = sunDir;
            strength = 1.05F * daylight * daylight * Mth.lerp(clearSky, 0.2F, 1.0F);
            float noon = smoothstep(0.0F, 0.45F, sunHeight);
            lightColor = new Vector3f(1.0F, 0.52F, 0.28F).lerp(new Vector3f(1.0F, 0.96F, 0.88F), noon);
        } else {
            lightDir = sunDir.negate(new Vector3f());
            strength = 0.16F * smoothstep(0.08F, 0.3F, -sunHeight) * clearSky;
            lightColor = new Vector3f(0.55F, 0.66F, 0.95F);
        }

        Vector3fc skyColor = sky.skyColor != null ? sky.skyColor : new Vector3f(0.47F, 0.65F, 1.0F);
        // A FAINT MOONLIGHT FLOOR KEEPS NIGHT GAS READABLE
        Vector3f ambient = new Vector3f(
            skyColor.x() * 0.55F + 0.035F,
            skyColor.y() * 0.55F + 0.040F,
            skyColor.z() * 0.55F + 0.060F);
        return new Lighting(lightDir, strength, lightColor, ambient);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
