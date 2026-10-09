package com.github.sethcg.buttcraft.client.gas;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;

/**
 * FART GAS FOR MINECRAFT 1.21.11.
 *
 * <p>THE VOLUMETRIC GPU RAY MARCHER TARGETS THE 26.3 RENDERPEARL API; THIS VERSION
 * KEEPS THE ORIGINAL POISON-PARTICLE CLOUD.
 */
public final class FartGas {

    private static final int POISON_COLOR = 0xFF000000 | MobEffects.POISON.value().getColor();
    private static final ColorParticleOption POISON_PARTICLE =
        ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, POISON_COLOR);

    private FartGas() {
    }

    public static void initialize() {
    }

    public static void emit(Minecraft client, double x, double y, double z, float yaw) {
        if (client.player == null) return;

        var level = client.player.level();
        RandomSource random = level.getRandom();

        double angle = Math.toRadians(yaw);

        double backwardX = Math.sin(angle);
        double backwardZ = -Math.cos(angle);

        double sideX = -backwardZ;
        double sideZ = backwardX;

        // SPAWN A CLOUD OF POISON PARTICLES AT WAIST LEVEL
        for (int i = 0; i < 28; i++) {
            double offsetX = (random.nextDouble() - 0.5) * 0.15;
            double offsetY = 0.45 + random.nextDouble() * 0.2;
            double offsetZ = (random.nextDouble() - 0.5) * 0.15;

            double speedX;
            double speedY;
            double speedZ;

            if (random.nextDouble() < 0.30) {
                speedX = (random.nextDouble() - 0.5) * 0.025;
                speedY = random.nextDouble() * 0.015;
                speedZ = (random.nextDouble() - 0.5) * 0.025;

            } else {
                double backwardSpeed = 0.08 + random.nextDouble() * 0.08;
                double sidewaysSpeed = (random.nextDouble() - 0.5) * 0.12;

                speedX = backwardX * backwardSpeed + sideX * sidewaysSpeed;
                speedZ = backwardZ * backwardSpeed + sideZ * sidewaysSpeed;
                speedY = (random.nextDouble() - 0.2) * 0.04;
            }

            level.addParticle(
                POISON_PARTICLE,
                x + offsetX,
                y + offsetY,
                z + offsetZ,
                speedX,
                speedY,
                speedZ);
        }
    }
}
