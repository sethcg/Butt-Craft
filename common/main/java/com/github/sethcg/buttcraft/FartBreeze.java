package com.github.sethcg.buttcraft;

// ONLY DEPENDS ON THE GAME TIME, SO EVERY CLIENT'S GAS AND THE SERVER'S FartClouds DRIFT TOGETHER
public final class FartBreeze {

    private static final double STRENGTH = 0.0011;

    private FartBreeze() {
    }

    public static double x(long gameTime) {
        return Math.cos(angle(gameTime)) * STRENGTH;
    }

    public static double z(long gameTime) {
        return Math.sin(angle(gameTime)) * STRENGTH;
    }

    public static double exposure(boolean hasSkyLight, double skyLight) {
        return hasSkyLight ? skyLight * skyLight : 0.0;
    }

    // WANDERS AROUND THE COMPASS OVER A FEW IN-GAME HOURS
    private static double angle(long gameTime) {
        return gameTime * 0.0007 + Math.sin(gameTime * 0.00023) * 2.0;
    }
}
