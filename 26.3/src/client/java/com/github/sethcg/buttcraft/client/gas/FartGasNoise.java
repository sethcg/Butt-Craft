package com.github.sethcg.buttcraft.client.gas;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import com.github.sethcg.buttcraft.ButtCraft;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.util.Util;

/**
 * PROCEDURAL, SEAMLESSLY TILING 3D NOISE VOLUME FOR THE GAS SHADER.
 *
 * <p>THE VOLUME IS 64x64x64 RGBA8, GENERATED ONCE ON A WORKER THREAD AND STORED AS A
 * 2D ATLAS OF 64 SLICES (8x8 GRID). EACH SLICE HAS A 1px GUTTER HOLDING THE WRAPPED
 * NEIGHBOUR TEXELS, SO HARDWARE BILINEAR FILTERING TILES CORRECTLY; THE SHADER
 * INTERPOLATES BETWEEN SLICES ITSELF. THIS WORKS ON EVERY BACKEND WITHOUT 3D TEXTURES.
 *
 * <ul>
 *   <li>R: PERLIN-WORLEY. PERLIN FBM REMAPPED BY INVERTED WORLEY (BILLOWY BASE SHAPE)</li>
 *   <li>G: INVERTED WORLEY FBM, FREQUENCIES 4/8/16</li>
 *   <li>B: INVERTED WORLEY FBM, FREQUENCIES 8/16/32</li>
 *   <li>A: PERLIN FBM (DOMAIN WARPING)</li>
 * </ul>
 */
public final class FartGasNoise {

    public static final int SIZE = 64;
    public static final int TILE = SIZE + 2;
    public static final int GRID = 8;
    public static final int ATLAS = TILE * GRID;

    private static final int SEED = 0x0B077C4A;

    private static @Nullable CompletableFuture<byte[]> pending;
    private static @Nullable GpuTexture texture;
    private static @Nullable GpuTextureView textureView;
    private static boolean failed;

    private FartGasNoise() {
    }

    /** STARTS BACKGROUND GENERATION. SAFE TO CALL MORE THAN ONCE. */
    public static void startGenerating() {
        if (pending == null && texture == null) {
            pending = CompletableFuture.supplyAsync(FartGasNoise::generateAtlas, Util.backgroundExecutor());
        }
    }

    /** RETURNS THE UPLOADED NOISE TEXTURE, OR NULL WHILE IT IS STILL BEING GENERATED. */
    public static @Nullable GpuTextureView textureView() {
        if (textureView != null || failed) {
            return textureView;
        }

        startGenerating();
        if (pending == null || !pending.isDone()) {
            return null;
        }

        try {
            upload(pending.join());
        } catch (RuntimeException e) {
            failed = true;
            ButtCraft.LOGGER.error("FAILED TO CREATE FART GAS NOISE TEXTURE.", e);
        } finally {
            pending = null;
        }

        return textureView;
    }

    public static void close() {
        if (textureView != null) {
            textureView.close();
            textureView = null;
        }

        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    private static void upload(byte[] pixels) {
        GpuDevice device = RenderSystem.getDevice();
        texture = device.createTexture(
            () -> "ButtCraft fart gas noise",
            GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM,
            ATLAS,
            ATLAS,
            1,
            1);

        ByteBuffer buffer = MemoryUtil.memAlloc(pixels.length);
        try {
            buffer.put(pixels).flip();
            device.createCommandEncoder().writeToTexture(texture, buffer, 0, 0, 0, 0, ATLAS, ATLAS);
        } finally {
            MemoryUtil.memFree(buffer);
        }

        textureView = device.createTextureView(texture);
        ButtCraft.LOGGER.info("FART GAS NOISE VOLUME UPLOADED ({}x{}x{}).", SIZE, SIZE, SIZE);
    }

    // =========================================================================
    // GENERATION
    // =========================================================================

    static byte[] generateAtlas() {
        long start = System.nanoTime();
        int voxels = SIZE * SIZE * SIZE;
        float[][] channels = new float[4][voxels];

        IntStream.range(0, SIZE).parallel().forEach(z -> {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    float px = (x + 0.5F) / SIZE;
                    float py = (y + 0.5F) / SIZE;
                    float pz = (z + 0.5F) / SIZE;
                    int index = (z * SIZE + y) * SIZE + x;

                    float worleyLow = worleyFbm(px, py, pz, 4);
                    float worleyHigh = worleyFbm(px, py, pz, 8);
                    float perlin = perlinFbm(px, py, pz, 4, 5) * 0.5F + 0.5F;

                    // PERLIN-WORLEY: REMAP PERLIN SO WORLEY CELLS CARVE BILLOWS INTO IT.
                    float perlinWorley = (perlin - (worleyLow - 1.0F)) / (2.0F - worleyLow);

                    channels[0][index] = perlinWorley;
                    channels[1][index] = worleyLow;
                    channels[2][index] = worleyHigh;
                    channels[3][index] = perlinFbm(px, py, pz, 3, 4) * 0.5F + 0.5F;
                }
            }
        });

        for (float[] channel : channels) {
            normalize(channel);
        }

        byte[] atlas = new byte[ATLAS * ATLAS * 4];
        for (int slice = 0; slice < SIZE; slice++) {
            int tileX = (slice % GRID) * TILE;
            int tileY = (slice / GRID) * TILE;
            for (int ty = 0; ty < TILE; ty++) {
                int y = Math.floorMod(ty - 1, SIZE);
                for (int tx = 0; tx < TILE; tx++) {
                    int x = Math.floorMod(tx - 1, SIZE);
                    int index = (slice * SIZE + y) * SIZE + x;
                    int out = ((tileY + ty) * ATLAS + tileX + tx) * 4;
                    for (int c = 0; c < 4; c++) {
                        atlas[out + c] = (byte) Math.round(channels[c][index] * 255.0F);
                    }
                }
            }
        }

        ButtCraft.LOGGER.info("GENERATED FART GAS NOISE VOLUME IN {} MS.", (System.nanoTime() - start) / 1_000_000L);
        return atlas;
    }

    private static void normalize(float[] values) {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }

        float range = Math.max(max - min, 1.0E-6F);
        for (int i = 0; i < values.length; i++) {
            values[i] = (values[i] - min) / range;
        }
    }

    // -------------------------------------------------------------------------
    // TILEABLE WORLEY (CELLULAR) NOISE
    // -------------------------------------------------------------------------

    private static float worleyFbm(float x, float y, float z, int frequency) {
        return worley(x, y, z, frequency) * 0.625F
            + worley(x, y, z, frequency * 2) * 0.25F
            + worley(x, y, z, frequency * 4) * 0.125F;
    }

    /** INVERTED F1 WORLEY NOISE WITH ONE FEATURE POINT PER CELL, PERIODIC IN [0,1)^3. */
    private static float worley(float x, float y, float z, int frequency) {
        float sx = x * frequency;
        float sy = y * frequency;
        float sz = z * frequency;
        int cx = (int) Math.floor(sx);
        int cy = (int) Math.floor(sy);
        int cz = (int) Math.floor(sz);
        float best = Float.MAX_VALUE;

        for (int oz = -1; oz <= 1; oz++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int ox = -1; ox <= 1; ox++) {
                    int nx = cx + ox;
                    int ny = cy + oy;
                    int nz = cz + oz;
                    int h = hash(Math.floorMod(nx, frequency), Math.floorMod(ny, frequency), Math.floorMod(nz, frequency), frequency);
                    float fx = nx + unit(h) - sx;
                    float fy = ny + unit(h * 0x27D4EB2D) - sy;
                    float fz = nz + unit(h * 0x165667B1) - sz;
                    best = Math.min(best, fx * fx + fy * fy + fz * fz);
                }
            }
        }

        return 1.0F - Math.min((float) Math.sqrt(best), 1.0F);
    }

    // -------------------------------------------------------------------------
    // TILEABLE PERLIN (GRADIENT) NOISE
    // -------------------------------------------------------------------------

    private static float perlinFbm(float x, float y, float z, int frequency, int octaves) {
        float sum = 0.0F;
        float amplitude = 1.0F;
        float total = 0.0F;
        for (int i = 0; i < octaves; i++) {
            sum += perlin(x * frequency, y * frequency, z * frequency, frequency) * amplitude;
            total += amplitude;
            amplitude *= 0.5F;
            frequency *= 2;
        }

        return sum / total;
    }

    private static float perlin(float x, float y, float z, int period) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        float fx = x - x0;
        float fy = y - y0;
        float fz = z - z0;
        float u = fade(fx);
        float v = fade(fy);
        float w = fade(fz);

        float n000 = gradient(x0, y0, z0, period, fx, fy, fz);
        float n100 = gradient(x0 + 1, y0, z0, period, fx - 1, fy, fz);
        float n010 = gradient(x0, y0 + 1, z0, period, fx, fy - 1, fz);
        float n110 = gradient(x0 + 1, y0 + 1, z0, period, fx - 1, fy - 1, fz);
        float n001 = gradient(x0, y0, z0 + 1, period, fx, fy, fz - 1);
        float n101 = gradient(x0 + 1, y0, z0 + 1, period, fx - 1, fy, fz - 1);
        float n011 = gradient(x0, y0 + 1, z0 + 1, period, fx, fy - 1, fz - 1);
        float n111 = gradient(x0 + 1, y0 + 1, z0 + 1, period, fx - 1, fy - 1, fz - 1);

        float nx00 = lerp(u, n000, n100);
        float nx10 = lerp(u, n010, n110);
        float nx01 = lerp(u, n001, n101);
        float nx11 = lerp(u, n011, n111);
        float nxy0 = lerp(v, nx00, nx10);
        float nxy1 = lerp(v, nx01, nx11);
        return lerp(w, nxy0, nxy1);
    }

    private static float gradient(int ix, int iy, int iz, int period, float dx, float dy, float dz) {
        int h = hash(Math.floorMod(ix, period), Math.floorMod(iy, period), Math.floorMod(iz, period), period + 977) & 15;
        // THE 12 EDGE DIRECTIONS OF A CUBE (WITH 4 REPEATS), AS IN IMPROVED PERLIN NOISE.
        float u = h < 8 ? dx : dy;
        float v = h < 4 ? dy : (h == 12 || h == 14 ? dx : dz);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6.0F - 15.0F) + 10.0F);
    }

    private static float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }

    // -------------------------------------------------------------------------
    // HASHING
    // -------------------------------------------------------------------------

    private static int hash(int x, int y, int z, int salt) {
        int h = SEED ^ salt * 0x2C1B3C6D;
        h ^= x * 0x8DA6B343;
        h = Integer.rotateLeft(h, 13) * 0x5BD1E995;
        h ^= y * 0xD8163841;
        h = Integer.rotateLeft(h, 15) * 0x27D4EB2F;
        h ^= z * 0xCB1AB31F;
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    private static float unit(int h) {
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return (h >>> 8) * (1.0F / 16777216.0F);
    }
}
