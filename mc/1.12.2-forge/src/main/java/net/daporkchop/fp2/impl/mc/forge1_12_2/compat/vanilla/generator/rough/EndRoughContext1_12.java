/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2020-2024 DaPorkchop_
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software
 * is furnished to do so, subject to the following conditions:
 *
 * Any persons and/or organizations using this software must include the above copyright notice and this permission notice,
 * provide sufficient credit to the original authors of the project (IE: DaPorkchop_), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.generator.rough;

import lombok.NonNull;
import net.daporkchop.fp2.api.world.registry.FGameRegistry;
import net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.FastRegistry;
import net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.biome.BiomeHelper;
import net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.biome.IBiomeProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import net.minecraft.world.gen.NoiseGeneratorSimplex;

import java.util.Random;

/**
 * Holds the initialized state for a vanilla {@code ChunkGeneratorEnd} emulation context.
 * <p>
 * Not thread-safe. End generation is much simpler than the overworld: a single fixed biome, no water, no
 * blending, just 3D density on an 8x4-block grid plus the island falloff function. Structures (obsidian spikes,
 * chorus plants, end cities/gateways) are intentionally ignored.
 * <p>
 * Like {@link VanillaRoughContext1_12}, this uses own {@link NoiseGeneratorOctaves} instances created with the
 * world seed in exactly the same order as the vanilla constructor, avoiding all private field access.
 *
 * @author DaPorkchop_
 */
public class EndRoughContext1_12 {
    //vanilla End density grid: 8 blocks horizontal, 4 blocks vertical
    public static final int EH_SHIFT = 3;
    public static final int EH_SIZE = 1 << EH_SHIFT;
    public static final int EV_SHIFT = 2;
    public static final int EV_SIZE = 1 << EV_SHIFT;

    //exact vanilla noise scales (see ChunkGeneratorEnd.getHeights, d0 = 684.412 * 2)
    private static final double NOISE_XZ = 684.412d * 2.0d;
    private static final double NOISE_Y = 684.412d;
    private static final double MAIN_XZ = NOISE_XZ / 80.0d;
    private static final double MAIN_Y = 4.277575000000001d;

    public final int size;
    public final int[] biomes; //fp2 biome ids

    protected final FGameRegistry registry;

    protected final IBiomeProvider biomeProvider;

    protected final NoiseGeneratorOctaves lperlinNoise1;
    protected final NoiseGeneratorOctaves lperlinNoise2;
    protected final NoiseGeneratorOctaves perlinNoise1;
    protected final NoiseGeneratorSimplex islandNoise;

    protected final float[] islandHeights; //island falloff value per column

    //reusable bulk noise regions (grid space, x-major layout: index = (x * nz + z) * ny + y)
    protected double[] pnrRegion = new double[0];
    protected double[] arRegion = new double[0];
    protected double[] brRegion = new double[0];
    protected int regionNX;
    protected int regionNY;
    protected int regionNZ;
    protected int regionGX0;
    protected int regionGY0;
    protected int regionGZ0;
    protected int strideX;
    protected int strideY;
    protected int strideZ;

    //current initialization position (block space)
    protected int baseX;
    protected int baseZ;
    protected int level = -1;

    public EndRoughContext1_12(@NonNull FGameRegistry registry, @NonNull WorldServer world, int size) {
        this.size = size;
        this.biomes = new int[this.size * this.size];

        this.registry = registry;

        this.biomeProvider = BiomeHelper.from(world.getBiomeProvider());

        //same order as ChunkGeneratorEnd's constructor. noiseGen5/noiseGen6 are unused for density in 1.12.2,
        //  but they must still be created to keep the Random sequence identical for islandNoise.
        Random rand = new Random(world.getSeed());
        this.lperlinNoise1 = new NoiseGeneratorOctaves(rand, 16);
        this.lperlinNoise2 = new NoiseGeneratorOctaves(rand, 16);
        this.perlinNoise1 = new NoiseGeneratorOctaves(rand, 8);
        new NoiseGeneratorOctaves(rand, 10); //noiseGen5 (unused)
        new NoiseGeneratorOctaves(rand, 16); //noiseGen6 (unused)
        this.islandNoise = new NoiseGeneratorSimplex(rand);

        this.islandHeights = new float[this.size * this.size];
    }

    /**
     * Initializes this context at the given position.
     *
     * @param baseX the base X coordinate (in blocks, already includes cache padding)
     * @param baseZ the base Z coordinate (in blocks, already includes cache padding)
     * @param level the detail level
     */
    public void init(int baseX, int baseZ, int level) {
        if (this.baseX == baseX && this.baseZ == baseZ && this.level == level) {
            return; //already initialized to the given position, we don't need to do anything
        }

        this.baseX = baseX;
        this.baseZ = baseZ;
        this.level = level;

        this.biomeProvider.generateBiomes(baseX, baseZ, level, this.size, this.biomes);

        //convert biome IDs to fp2 IDs
        for (int i = 0; i < this.size * this.size; i++) {
            this.biomes[i] = this.registry.biome2id(FastRegistry.getBiome(this.biomes[i]));
        }

        //precompute island falloff per column (depends only on x/z)
        for (int dx = 0; dx < this.size; dx++) {
            int blockX = baseX + (dx << level);
            int chunkX = Math.floorDiv(blockX, 16);
            double gridX = (double) blockX / EH_SIZE - chunkX * 2;
            for (int dz = 0; dz < this.size; dz++) {
                int blockZ = baseZ + (dz << level);
                int chunkZ = Math.floorDiv(blockZ, 16);
                double gridZ = (double) blockZ / EH_SIZE - chunkZ * 2;
                this.islandHeights[dx * this.size + dz] = getIslandHeightValue(chunkX, chunkZ, gridX, gridZ, this.islandNoise);
            }
        }
    }

    protected int biomeIndex(int x, int z) {
        return ((x - this.baseX) >> this.level) * this.size + ((z - this.baseZ) >> this.level);
    }

    public int getBiome(int x, int z) {
        return this.biomes[this.biomeIndex(x, z)];
    }

    /**
     * Vanilla {@code ChunkGeneratorEnd.getIslandHeightValue}, generalized to fractional grid coordinates
     * (vanilla only ever calls it with whole grid nodes, but the formula is continuous).
     */
    protected static float getIslandHeightValue(int chunkX, int chunkZ, double gridX, double gridZ, @NonNull NoiseGeneratorSimplex islandNoise) {
        double f = chunkX * 2 + gridX;
        double f1 = chunkZ * 2 + gridZ;
        double f2 = 100.0d - Math.sqrt(f * f + f1 * f1) * 8.0d;
        if (f2 > 80.0d) {
            f2 = 80.0d;
        }
        if (f2 < -100.0d) {
            f2 = -100.0d;
        }

        for (int i = -12; i <= 12; i++) {
            for (int j = -12; j <= 12; j++) {
                long k = (long) chunkX + i;
                long l = (long) chunkZ + j;
                if (k * k + l * l > 4096L && islandNoise.getValue((double) k, (double) l) < -0.9d) {
                    double f3 = (Math.abs(k) * 3439.0d + Math.abs(l) * 147.0d) % 13.0d + 9.0d;
                    double g = gridX - i * 2;
                    double g1 = gridZ - j * 2;
                    double f4 = 100.0d - Math.sqrt(g * g + g1 * g1) * f3;
                    if (f4 > 80.0d) {
                        f4 = 80.0d;
                    }
                    if (f4 < -100.0d) {
                        f4 = -100.0d;
                    }
                    if (f4 > f2) {
                        f2 = f4;
                    }
                }
            }
        }
        return (float) f2;
    }

    /**
     * Generates a 3d grid of densities.
     * <p>
     * The generated region will be a cube with a side length of {@link #size}, and the lowest point is at
     * ({@link #baseX}, {@code baseY}, {@link #baseZ}) in blocks (including cache padding). Vanilla only
     * generates terrain below y=128; anything at or above returns strong negative density (air).
     *
     * @param out   the array to write the generated density values to
     * @param baseY the base Y coordinate (in blocks, already includes cache padding)
     */
    public void get3d(@NonNull double[] out, int baseY) {
        int level = this.level;
        int step = 1 << level;
        int size = this.size;

        //End grid is 8 blocks horizontal / 4 blocks vertical: exact at level >= 3 / level >= 2 respectively,
        //  otherwise generate the covering native grid and trilinearly interpolate.
        boolean exactH = level >= EH_SHIFT;
        boolean exactV = level >= EV_SHIFT;

        int minBlockX = this.baseX;
        int minBlockY = baseY;
        int minBlockZ = this.baseZ;
        int spanBlocks = (size - 1) * step;

        if (exactH) {
            this.strideX = step >> EH_SHIFT;
            this.regionGX0 = Math.floorDiv(minBlockX, EH_SIZE) / this.strideX;
            this.strideZ = this.strideX;
            this.regionGZ0 = Math.floorDiv(minBlockZ, EH_SIZE) / this.strideZ;
            this.regionNX = this.regionNZ = size;
        } else {
            this.strideX = this.strideZ = 1;
            this.regionGX0 = Math.floorDiv(minBlockX, EH_SIZE);
            this.regionGZ0 = Math.floorDiv(minBlockZ, EH_SIZE);
            this.regionNX = this.regionNZ = (spanBlocks >> EH_SHIFT) + 2;
        }

        if (exactV) {
            this.strideY = step >> EV_SHIFT;
            this.regionGY0 = Math.floorDiv(minBlockY, EV_SIZE) / this.strideY;
            this.regionNY = size;
        } else {
            this.strideY = 1;
            this.regionGY0 = Math.floorDiv(minBlockY, EV_SIZE);
            this.regionNY = (spanBlocks >> EV_SHIFT) + 2;
        }

        int count = this.regionNX * this.regionNY * this.regionNZ;
        this.pnrRegion = this.perlinNoise1.generateNoiseOctaves(ensureCapacity(this.pnrRegion, count),
                this.regionGX0, this.regionGY0, this.regionGZ0, this.regionNX, this.regionNY, this.regionNZ,
                MAIN_XZ * this.strideX, MAIN_Y * this.strideY, MAIN_XZ * this.strideZ);
        this.arRegion = this.lperlinNoise1.generateNoiseOctaves(ensureCapacity(this.arRegion, count),
                this.regionGX0, this.regionGY0, this.regionGZ0, this.regionNX, this.regionNY, this.regionNZ,
                NOISE_XZ * this.strideX, NOISE_Y * this.strideY, NOISE_XZ * this.strideZ);
        this.brRegion = this.lperlinNoise2.generateNoiseOctaves(ensureCapacity(this.brRegion, count),
                this.regionGX0, this.regionGY0, this.regionGZ0, this.regionNX, this.regionNY, this.regionNZ,
                NOISE_XZ * this.strideX, NOISE_Y * this.strideY, NOISE_XZ * this.strideZ);

        for (int i = 0, dx = 0; dx < size; dx++) {
            int blockX = minBlockX + (dx << level);
            double fx = exactH ? dx : ((double) blockX / EH_SIZE - this.regionGX0);
            for (int dy = 0; dy < size; dy++) {
                int blockY = minBlockY + (dy << level);
                double fy = exactV ? dy : ((double) blockY / EV_SIZE - this.regionGY0);
                for (int dz = 0; dz < size; dz++, i++) {
                    int blockZ = minBlockZ + (dz << level);
                    double fz = exactH ? dz : ((double) blockZ / EH_SIZE - this.regionGZ0);

                    double density;
                    if (blockY >= 128) {
                        density = -30.0d; //vanilla never writes blocks at y >= 128
                    } else {
                        double pnr = sampleTrilinear(this.pnrRegion, this.regionNX, this.regionNY, this.regionNZ, fx, fy, fz);
                        double ar = sampleTrilinear(this.arRegion, this.regionNX, this.regionNY, this.regionNZ, fx, fy, fz);
                        double br = sampleTrilinear(this.brRegion, this.regionNX, this.regionNY, this.regionNZ, fx, fy, fz);

                        double d2 = ar / 512.0d;
                        double d3 = br / 512.0d;
                        double d5 = (pnr / 10.0d + 1.0d) / 2.0d;
                        double d4 = d5 < 0.0d ? d2 : (d5 > 1.0d ? d3 : d2 + (d3 - d2) * d5);
                        d4 -= 8.0d;
                        d4 += this.islandHeights[dx * size + dz];

                        //pin the top down, exactly like vanilla (j1 is the absolute y grid index)
                        double gridY = (double) blockY / EV_SIZE;
                        if (gridY > 14.0d) {
                            double t = (gridY - 14.0d) / 64.0d;
                            if (t > 1.0d) {
                                t = 1.0d;
                            }
                            d4 = d4 * (1.0d - t) + -3000.0d * t;
                        }
                        if (gridY < 8.0d) {
                            double t = (8.0d - gridY) / 7.0d;
                            d4 = d4 * (1.0d - t) + -30.0d * t;
                        }

                        density = d4;
                    }

                    out[i] = density;
                }
            }
        }
    }

    protected static double[] ensureCapacity(double[] arr, int length) {
        return arr.length >= length ? arr : new double[length];
    }

    protected static double sampleTrilinear(@NonNull double[] region, int nx, int ny, int nz, double fx, double fy, double fz) {
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        int z0 = (int) Math.floor(fz);
        double tx = fx - x0;
        double ty = fy - y0;
        double tz = fz - z0;

        //clamp to valid range (can go out of bounds by rounding at tile edges in interp mode)
        if (x0 < 0) {
            x0 = 0;
            tx = 0.0d;
        } else if (x0 >= nx - 1) {
            x0 = nx - 2;
            tx = 1.0d;
        }
        if (y0 < 0) {
            y0 = 0;
            ty = 0.0d;
        } else if (y0 >= ny - 1) {
            y0 = ny - 2;
            ty = 1.0d;
        }
        if (z0 < 0) {
            z0 = 0;
            tz = 0.0d;
        } else if (z0 >= nz - 1) {
            z0 = nz - 2;
            tz = 1.0d;
        }

        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;

        //x-major layout: index = (x * nz + z) * ny + y
        double v000 = region[(x0 * nz + z0) * ny + y0];
        double v001 = region[(x0 * nz + z0 + 1) * ny + y0];
        double v010 = region[(x0 * nz + z0) * ny + y0 + 1];
        double v011 = region[(x0 * nz + z0 + 1) * ny + y0 + 1];
        double v100 = region[(x1 * nz + z0) * ny + y0];
        double v101 = region[(x1 * nz + z0 + 1) * ny + y0];
        double v110 = region[(x1 * nz + z0) * ny + y0 + 1];
        double v111 = region[(x1 * nz + z0 + 1) * ny + y0 + 1];

        double v00 = v000 + (v001 - v000) * tz;
        double v01 = v010 + (v011 - v010) * tz;
        double v10 = v100 + (v101 - v100) * tz;
        double v11 = v110 + (v111 - v110) * tz;

        double v0 = v00 + (v01 - v00) * ty;
        double v1 = v10 + (v11 - v10) * ty;

        return v0 + (v1 - v0) * tx;
    }
}
