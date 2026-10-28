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

/**
 * CONTENTS OF THE SHADERS' {@code FartGasInfo} UNIFORM BLOCK (STD140). THE LAYOUT HERE MUST
 * MATCH THE BLOCK IN {@code shaders/include/fart_gas_common.glsl}.
 *
 * <p>ONLY BUILDS THE BYTES; THE VERSION-SPECIFIC {@code FartGasRenderer} OWNS THE GPU BUFFER.
 */
public final class FartGasUniforms {

    // CAMERA POSITION IS WRAPPED BY THE LCM OF THE SHADER'S NOISE PERIODS (4, 1.5, 0.5 BLOCKS).
    private static final double NOISE_PERIOD = 12.0;
    // EVERY NOISE SCROLL SPEED IN fart_gas.fsh TIMES THIS IS A WHOLE NUMBER, SO THE NOISE
    // DOESN'T JUMP WHEN TIME WRAPS.
    private static final double TIME_PERIOD_SECONDS = 2000.0;

    // HOW MUCH LIGHT THE GAS SCATTERS BACK. LOWER IS DARKER, WITHOUT CHANGING HOW OPAQUE IT IS.
    private static final Vector3fc GAS_ALBEDO = new Vector3f(0.48F, 0.63F, 0.31F);
    private static final float GAS_DENSITY = 1.6F;

    // PUFFS FAINTER THAN THIS ARE NOT UPLOADED. THE SHADER STOPS COLLECTING SHADOW-ONLY PUFFS
    // ONCE ITS HIT LIST IS FULL, SO THE REST ARE UPLOADED DENSEST FIRST. (ON THE VIEW RAY IT
    // KEEPS THE PUFFS THAT ADD THE MOST GAS, WHATEVER THEIR ORDER.)
    private static final float MIN_VISIBLE_DENSITY = 0.002F;

    // WALL OFFSETS ARE 16-BIT FIXED POINT: -WALL_RANGE TO +WALL_RANGE BLOCKS FROM THE PUFF'S CENTER,
    // WALL_STEPS_PER_BLOCK TO A BLOCK. A SIDE WITH NO WALL NEARBY GOES AT THE END OF THE RANGE, WELL
    // OUTSIDE THE PUFF. MUST MATCH WALL_RANGE / WALL_STEP IN fart_gas_common.glsl.
    private static final float WALL_RANGE = 8.0F;
    private static final float WALL_STEPS_PER_BLOCK = 4096.0F;

    // FIELD COVERAGE AT THE CAMERA WHERE IT STARTS COUNTING AS INSIDE THE GAS, AND WHERE FULLY.
    private static final float IMMERSION_START = 0.05F;
    private static final float IMMERSION_FULL = 0.4F;


    // SORTING SCRATCH (RENDER THREAD ONLY).
    private static final FartGasSimulation.Puff[] SORTED = new FartGasSimulation.Puff[FartGasSimulation.MAX_PUFFS];
    private static final float[] SORTED_DENSITY = new float[FartGasSimulation.MAX_PUFFS];
    private static final FartGasSimulation.Puff[] GROUPED = new FartGasSimulation.Puff[FartGasSimulation.MAX_PUFFS];
    private static final float[] GROUPED_DENSITY = new float[FartGasSimulation.MAX_PUFFS];
    private static final int[] RECT_ORDER = new int[FartGasSimulation.MAX_RECTS];
    private static final int[] RECT_ENDS = new int[FartGasSimulation.MAX_RECTS];

    /** THE LAST UPLOADED CAMERA IMMERSION (SEE immersion), FOR {@link FartGasBenchmark}. */
    static float lastImmersion;

    /** SIZE OF THE BLOCK IN BYTES. */
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
        for (int i = 0; i < FartGasSimulation.MAX_RECTS / 4; i++) {
            size.putIVec4();
        }
        // EACH PUFF IS A Puff STRUCT (SEE fart_gas_common.glsl): TWO vec4s AND A uvec4.
        for (int i = 0; i < FartGasSimulation.MAX_PUFFS; i++) {
            size.putVec4().putVec4().putIVec4();
        }
        SIZE = size.get();
    }

    /** THE DOMINANT CELESTIAL LIGHT (DIRECTION TOWARD IT, STRENGTH, COLOR) AND THE SKY AMBIENT COLOR. */
    public record Lighting(Vector3fc direction, float strength, Vector3fc color, Vector3fc ambient) {
    }

    /**
     * WHAT TEMPORAL SMOOTHING NEEDS ABOUT THE LAST FRAME: THE CAMERA'S MOVE SINCE THEN AND ITS
     * ROTATION. frame VARIES THE MARCH'S JITTER; historyWeight 0 MEANS THERE IS NO USABLE HISTORY.
     */
    public record Temporal(int frame, float historyWeight, Vector3fc cameraMotion, Matrix4fc previousWorldToView) {
        public static final Temporal NONE = new Temporal(0, 0.0F, new Vector3f(), new Matrix4f());
    }

    /**
     * puffs: HOW MANY PUFFS WERE UPLOADED (0 = NOTHING VISIBLE TO DRAW). rects: HOW MANY SCREEN
     * RECTANGLES THEY FILL. largestRun: THE MOST PUFFS IN ONE RECTANGLE, ROUGHLY THE MOST A
     * PIXEL TESTS UNLESS RECTANGLES OVERLAP ON SCREEN.
     */
    public record Upload(int puffs, int rects, int largestRun) {
    }

    private FartGasUniforms() {
    }

    public static Upload write(
        ByteBuffer data,
        LevelRenderState levelState,
        List<FartGasSimulation.Puff> puffs,
        FartGasSettings.Values quality,
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
        lastImmersion = immersion(count, cameraPos, partial);
        builder.putVec4(lighting.ambient().x(), lighting.ambient().y(), lighting.ambient().z(), lastImmersion);
        builder.putVec4(GAS_ALBEDO.x(), GAS_ALBEDO.y(), GAS_ALBEDO.z(), GAS_DENSITY);

        Vector3fc motion = temporal.cameraMotion();
        builder.putVec4(motion.x(), motion.y(), motion.z(), temporal.historyWeight());

        builder.putIVec4(count, quality.maxViewSteps(), quality.shadowSteps(), quality.resolutionDivisor());
        builder.putIVec4(temporal.frame(), rects, 0, 0);
        for (int r = 0; r < FartGasSimulation.MAX_RECTS; r += 4) {
            builder.putIVec4(RECT_ENDS[r], RECT_ENDS[r + 1], RECT_ENDS[r + 2], RECT_ENDS[r + 3]);
        }

        // ONLY THE VISIBLE PUFFS: THE SHADERS NEVER READ PAST THE COUNT, SO THE REST OF THE
        // ARRAY CAN HOLD WHATEVER WAS THERE BEFORE.
        for (int i = 0; i < count; i++) {
            FartGasSimulation.Puff puff = GROUPED[i];
            float radius = puff.radius(partial);
            double px = puff.x(partial);
            double py = puff.y(partial);
            double pz = puff.z(partial);
            builder.putVec4((float) (px - cameraPos.x), (float) (py - cameraPos.y), (float) (pz - cameraPos.z), radius);
            builder.putVec4(GROUPED_DENSITY[i], 1.0F / (radius * radius), puff.sunDepth, puff.skyDepth);
            builder.putIVec4(
                pack(unorm16(puff.skyLight), unorm16(puff.blockLight)),
                pack(wall(puff.wallMinX, px, -WALL_RANGE), wall(puff.wallMinY, py, -WALL_RANGE)),
                pack(wall(puff.wallMinZ, pz, -WALL_RANGE), wall(puff.wallMaxX, px, WALL_RANGE)),
                pack(wall(puff.wallMaxY, py, WALL_RANGE), wall(puff.wallMaxZ, pz, WALL_RANGE)));
            SORTED[i] = null;
            GROUPED[i] = null;
        }

        int largestRun = 0;
        for (int r = 0; r < rects; r++) {
            largestRun = Math.max(largestRun, RECT_ENDS[r] - (r == 0 ? 0 : RECT_ENDS[r - 1]));
        }

        return new Upload(count, rects, largestRun);
    }

    /**
     * HOW FAR INSIDE THE GAS THE CAMERA IS, 0-1: THE SHADER'S SMOOTH FIELD COVERAGE AT THE CAMERA
     * (WITHOUT WALLS OR NOISE), FROM IMMERSION_START TO IMMERSION_FULL. FROM INSIDE, THE GAS FILLS
     * THE SCREEN, SO fart_gas.fsh SKIPS ITS SHADOW MARCH AND ONLY MARCHES THE NEAREST GAS.
     */
    private static float immersion(int count, Vec3 cameraPos, float partial) {
        float weightSum = 0.0F;
        for (int i = 0; i < count; i++) {
            FartGasSimulation.Puff puff = GROUPED[i];
            float radius = puff.radius(partial);
            double dx = puff.x(partial) - cameraPos.x;
            double dy = puff.y(partial) - cameraPos.y;
            double dz = puff.z(partial) - cameraPos.z;
            float d2 = (float) ((dx * dx + dy * dy + dz * dz) / (radius * radius));
            if (d2 < 1.0F) {
                weightSum += (1.0F - d2) * (1.0F - d2);
            }
        }

        return smoothstep(IMMERSION_START, IMMERSION_FULL, Math.min(weightSum * FartGasSimulation.FIELD_COVERAGE_SCALE, 1.0F));
    }

    // A WALL'S OFFSET FROM THE PUFF'S CENTER AS 16-BIT FIXED POINT (SEE WALL_RANGE), OR noWall
    // IF THERE IS NONE ON THAT SIDE
    private static int wall(double coordinate, double center, float noWall) {
        double offset = Double.isFinite(coordinate) ? coordinate - center : noWall;
        return (int) Mth.clamp(Math.round((offset + WALL_RANGE) * WALL_STEPS_PER_BLOCK), 0L, 0xFFFFL);
    }

    private static int unorm16(float value) {
        return Math.round(Mth.clamp(value, 0.0F, 1.0F) * 0xFFFF);
    }

    // TWO 16-BIT VALUES IN ONE uint, low IN THE LOW BITS
    private static int pack(int low, int high) {
        return low | high << 16;
    }

    /** FILLS SORTED / SORTED_DENSITY WITH THE VISIBLE PUFFS, DENSEST FIRST, AND RETURNS HOW MANY. */
    private static int sortVisible(List<FartGasSimulation.Puff> puffs, float partial) {
        int count = 0;
        for (FartGasSimulation.Puff puff : puffs) {
            float density = puff.density(partial);
            if (density < MIN_VISIBLE_DENSITY) {
                continue;
            }

            // INSERTION SORT (FEW PUFFS). WHEN FULL, THE FAINTEST FALLS OFF THE END.
            if (count == FartGasSimulation.MAX_PUFFS) {
                if (density <= SORTED_DENSITY[count - 1]) {
                    continue;
                }
                count--;
            }

            int j = count;
            while (j > 0 && SORTED_DENSITY[j - 1] < density) {
                SORTED[j] = SORTED[j - 1];
                SORTED_DENSITY[j] = SORTED_DENSITY[j - 1];
                j--;
            }

            SORTED[j] = puff;
            SORTED_DENSITY[j] = density;
            count++;
        }

        return count;
    }

    /**
     * COPIES THE SORTED PUFFS INTO GROUPED, ONE RUN PER SCREEN RECTANGLE (SEE
     * {@link FartGasSimulation#MAX_RECTS}), AND FILLS RECT_ENDS WITH WHERE EACH RUN ENDS. THE
     * RUNS GO IN ORDER OF THEIR DENSEST PUFF AND STAY DENSEST FIRST INSIDE, SO A FULL HIT LIST
     * STILL MOSTLY DROPS THE FAINTEST PUFFS. RETURNS HOW MANY RECTANGLES HAVE PUFFS.
     */
    private static int groupByRect(int count) {
        Arrays.fill(RECT_ORDER, -1);
        int rects = 0;
        for (int i = 0; i < count; i++) {
            int rect = rectOf(SORTED[i]);
            if (RECT_ORDER[rect] < 0) {
                RECT_ORDER[rect] = rects++;
            }
        }

        // COUNT EACH RUN, THEN TURN THE COUNTS INTO END INDICES
        Arrays.fill(RECT_ENDS, 0);
        for (int i = 0; i < count; i++) {
            RECT_ENDS[RECT_ORDER[rectOf(SORTED[i])]]++;
        }

        for (int r = 1; r < FartGasSimulation.MAX_RECTS; r++) {
            RECT_ENDS[r] += RECT_ENDS[r - 1];
        }

        // FILL EACH RUN FROM ITS END BACKWARD, WALKING THE SORTED PUFFS BACKWARD, SO EACH RUN
        // KEEPS THEIR DENSEST-FIRST ORDER. RECT_ENDS ENDS UP HOLDING EACH RUN'S START...
        for (int i = count - 1; i >= 0; i--) {
            int run = RECT_ORDER[rectOf(SORTED[i])];
            int slot = --RECT_ENDS[run];
            GROUPED[slot] = SORTED[i];
            GROUPED_DENSITY[slot] = SORTED_DENSITY[i];
        }

        // ...SO SHIFT IT BACK TO THE ENDS. EVERY UNUSED RECTANGLE ENDS WHERE THE LAST ONE DOES.
        for (int r = 0; r < FartGasSimulation.MAX_RECTS; r++) {
            RECT_ENDS[r] = r + 1 < rects ? RECT_ENDS[r + 1] : count;
        }

        return rects;
    }

    private static int rectOf(FartGasSimulation.Puff puff) {
        return Mth.clamp(puff.rect, 0, FartGasSimulation.MAX_RECTS - 1);
    }

    /** DIRECTION/COLOR OF THE DOMINANT CELESTIAL LIGHT AND THE SKY AMBIENT COLOR. */
    public static Lighting lighting(SkyRenderState sky) {
        if (sky.skybox != DimensionType.Skybox.OVERWORLD) {
            // NO SUN: FLAT, DIMENSION-TINTED AMBIENT (PUFF SKY LIGHT IS FORCED TO 1 IN SKYLESS DIMENSIONS).
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
        // AMBIENT FOLLOWS THE SKY COLOR, WITH A FAINT MOONLIGHT FLOOR SO NIGHT GAS STAYS READABLE.
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
