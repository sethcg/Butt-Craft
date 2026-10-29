package com.github.sethcg.buttcraft;

/**
 * THE SLOWLY TURNING BREEZE THAT CARRIES FART GAS OUTDOORS.
 *
 * <p>IT ONLY DEPENDS ON THE GAME TIME, SO EVERY CLIENT'S VISIBLE GAS AND THE SERVER'S STENCH
 * AREA (SEE {@link FartClouds}) ARE BLOWN THE SAME WAY AT THE SAME MOMENT.
 */
public final class FartBreeze {

    // PUSH PER TICK (BLOCKS / TICK, EACH TICK) WITH A CLEAR VIEW OF THE SKY
    private static final double STRENGTH = 0.0011;

    private FartBreeze() {
    }

    public static double x(long gameTime) {
        return Math.cos(angle(gameTime)) * STRENGTH;
    }

    public static double z(long gameTime) {
        return Math.sin(angle(gameTime)) * STRENGTH;
    }

    /** HOW MUCH OF THE BREEZE REACHES GAS WITH THIS SKY LIGHT (0-1): NONE INDOORS OR IN SKYLESS DIMENSIONS. */
    public static double exposure(boolean hasSkyLight, double skyLight) {
        return hasSkyLight ? skyLight * skyLight : 0.0;
    }

    // WANDERS AROUND THE COMPASS OVER A FEW IN-GAME HOURS
    private static double angle(long gameTime) {
        return gameTime * 0.0007 + Math.sin(gameTime * 0.00023) * 2.0;
    }
}
