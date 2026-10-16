package com.github.sethcg.buttcraft.client.gas;

import java.nio.ByteBuffer;
import java.util.List;

import org.joml.Matrix4f;
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

    private static final Vector3fc GAS_ALBEDO = new Vector3f(0.64F, 0.74F, 0.40F);
    private static final float GAS_DENSITY = 1.6F;

    // PUFFS FAINTER THAN THIS ARE NOT UPLOADED. THE SHADER STOPS COLLECTING PUFFS ALONG A RAY
    // ONCE ITS HIT LIST IS FULL, SO THE REST ARE UPLOADED DENSEST FIRST.
    private static final float MIN_VISIBLE_DENSITY = 0.002F;

    // SORTING SCRATCH (RENDER THREAD ONLY).
    private static final FartGasSimulation.Puff[] SORTED = new FartGasSimulation.Puff[FartGasSimulation.MAX_PUFFS];
    private static final float[] SORTED_DENSITY = new float[FartGasSimulation.MAX_PUFFS];

    /** SIZE OF THE BLOCK IN BYTES. */
    public static final int SIZE;

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
        SIZE = size.get();
    }

    private FartGasUniforms() {
    }

    /** RETURNS HOW MANY PUFFS WERE UPLOADED (0 = NOTHING VISIBLE TO DRAW). */
    public static int write(ByteBuffer data, LevelRenderState levelState, List<FartGasSimulation.Puff> puffs, FartGasSettings.Values quality) {
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

        putLighting(builder, sky);

        builder.putVec4(GAS_ALBEDO.x(), GAS_ALBEDO.y(), GAS_ALBEDO.z(), GAS_DENSITY);

        int count = sortVisible(puffs, partial);
        builder.putIVec4(count, quality.maxViewSteps(), quality.shadowSteps(), quality.resolutionDivisor());

        // ONLY THE VISIBLE PUFFS: THE SHADERS NEVER READ PAST THE COUNT, SO THE REST OF THE
        // ARRAY CAN HOLD WHATEVER WAS THERE BEFORE.
        for (int i = 0; i < count; i++) {
            FartGasSimulation.Puff puff = SORTED[i];
            float radius = puff.radius(partial);
            builder.putVec4(
                (float) (puff.x(partial) - cameraPos.x),
                (float) (puff.y(partial) - cameraPos.y),
                (float) (puff.z(partial) - cameraPos.z),
                radius);
            builder.putVec4(SORTED_DENSITY[i], puff.skyLight, puff.blockLight, 1.0F / (radius * radius));
            SORTED[i] = null;
        }

        return count;
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

    /** DIRECTION/COLOR OF THE DOMINANT CELESTIAL LIGHT AND THE SKY AMBIENT COLOR. */
    private static void putLighting(Std140Builder builder, SkyRenderState sky) {
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
