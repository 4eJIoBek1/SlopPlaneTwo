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

import static net.daporkchop.lib.common.math.PMath.floorI;

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

    //vanilla's density noise runs always cover the full y lattice: 33 nodes, 4 blocks apart
    public static final int COLUMN_NODES = 33;

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
    protected final double[] colBuf = new double[COLUMN_NODES]; //scratch for a single column of octave noise
    protected int regionNX;
    protected int regionNY;
    protected int regionNZ;
    protected int regionGX0;
    protected int regionGZ0;
    protected int regionHStride; //column spacing in vanilla lattice units (1, or step/8 for level >= EH_SHIFT)

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

        //End grid is 8 blocks horizontal / 4 blocks vertical: samples coincide with vanilla lattice nodes at
        //  level >= 3 / level >= 2 respectively, otherwise generate the covering native grid and trilinearly
        //  interpolate.
        boolean exactH = level >= EH_SHIFT;

        int minBlockX = this.baseX;
        int minBlockY = baseY;
        int minBlockZ = this.baseZ;
        int spanBlocks = (size - 1) * step;

        //Vanilla always samples the three density noises on the native lattice: columns every 8 blocks, y nodes
        //  every 4 blocks, with the y index run starting at 0 (the full run is 3*33*3 nodes per chunk). The y run
        //  MUST start at 0 (and not at the first node we actually need) because NoiseGeneratorImproved keeps a
        //  per-column cache of x-interpolated corner gradients which is only refreshed when floor(y) changes: the
        //  values at a node depend on the entire run prefix. Sampling a shifted or shortened run silently produces
        //  different (wrong) values. Columns are independent of each other, so one vanilla run per column is safe.
        this.regionGX0 = Math.floorDiv(minBlockX, EH_SIZE);
        this.regionGZ0 = Math.floorDiv(minBlockZ, EH_SIZE);
        if (exactH) { //samples are grid-aligned: one column per tile node
            this.regionHStride = step >> EH_SHIFT;
            this.regionNX = this.regionNZ = size;
        } else { //finer than the vanilla grid: cover the native columns and interpolate
            this.regionHStride = 1;
            this.regionNX = this.regionNZ = (spanBlocks >> EH_SHIFT) + 2;
        }
        //y run prefix: 0..(highest needed node + 1), capped at the vanilla length. The region must always cover at
        //  least two nodes (nothing can be interpolated on a single node), which can happen for tiles far below y=0.
        this.regionNY = Math.max(Math.min(((minBlockY + spanBlocks) >> EV_SHIFT) + 2, COLUMN_NODES), 2);

        this.pnrRegion = ensureCapacity(this.pnrRegion, this.regionNX * this.regionNZ * this.regionNY);
        this.arRegion = ensureCapacity(this.arRegion, this.regionNX * this.regionNZ * this.regionNY);
        this.brRegion = ensureCapacity(this.brRegion, this.regionNX * this.regionNZ * this.regionNY);

        for (int ix = 0; ix < this.regionNX; ix++) {
            int gx = this.regionGX0 + ix * this.regionHStride;
            for (int iz = 0; iz < this.regionNZ; iz++) {
                int gz = this.regionGZ0 + iz * this.regionHStride;
                int idx = (ix * this.regionNZ + iz) * this.regionNY;

                this.perlinNoise1.generateNoiseOctaves(this.colBuf, gx, 0, gz, 1, this.regionNY, 1, MAIN_XZ, MAIN_Y, MAIN_XZ);
                System.arraycopy(this.colBuf, 0, this.pnrRegion, idx, this.regionNY);
                this.lperlinNoise1.generateNoiseOctaves(this.colBuf, gx, 0, gz, 1, this.regionNY, 1, NOISE_XZ, NOISE_Y, NOISE_XZ);
                System.arraycopy(this.colBuf, 0, this.arRegion, idx, this.regionNY);
                this.lperlinNoise2.generateNoiseOctaves(this.colBuf, gx, 0, gz, 1, this.regionNY, 1, NOISE_XZ, NOISE_Y, NOISE_XZ);
                System.arraycopy(this.colBuf, 0, this.brRegion, idx, this.regionNY);
            }
        }

        for (int i = 0, dx = 0; dx < size; dx++) {
            int blockX = minBlockX + (dx << level);
            int nx0;
            double tx;
            if (exactH) { //samples coincide with the generated columns
                nx0 = dx;
                tx = 0.0d;
            } else {
                double fx = (double) blockX / EH_SIZE - this.regionGX0;
                nx0 = floorI(fx);
                tx = fx - nx0;
            }
            if (nx0 < 0) {
                nx0 = 0;
                tx = 0.0d;
            } else if (nx0 >= this.regionNX - 1) { //the last sample is the far edge of the grid, interpolate on the last cell
                nx0 = this.regionNX - 2;
                tx = 1.0d;
            }
            for (int dy = 0; dy < size; dy++) {
                int blockY = minBlockY + (dy << level);
                double fy = (double) blockY / EV_SIZE; //absolute y lattice index, same as vanilla's j1
                int ny0 = floorI(fy);
                double ty = fy - ny0;
                if (ny0 < 0) {
                    ny0 = 0;
                    ty = 0.0d;
                } else if (ny0 >= this.regionNY - 1) {
                    ny0 = this.regionNY - 2;
                    ty = 1.0d;
                }
                for (int dz = 0; dz < size; dz++, i++) {
                    int blockZ = minBlockZ + (dz << level);
                    int nz0;
                    double tz;
                    if (exactH) {
                        nz0 = dz;
                        tz = 0.0d;
                    } else {
                        double fz = (double) blockZ / EH_SIZE - this.regionGZ0;
                        nz0 = floorI(fz);
                        tz = fz - nz0;
                    }
                    if (nz0 < 0) {
                        nz0 = 0;
                        tz = 0.0d;
                    } else if (nz0 >= this.regionNZ - 1) {
                        nz0 = this.regionNZ - 2;
                        tz = 1.0d;
                    }

                    double density;
                    if (blockY >= 128) {
                        density = -30.0d; //vanilla never writes blocks at y >= 128
                    } else {
                        double pnr = sampleTrilinear(this.pnrRegion, this.regionNX, this.regionNY, this.regionNZ, nx0, ny0, nz0, tx, ty, tz);
                        double ar = sampleTrilinear(this.arRegion, this.regionNX, this.regionNY, this.regionNZ, nx0, ny0, nz0, tx, ty, tz);
                        double br = sampleTrilinear(this.brRegion, this.regionNX, this.regionNY, this.regionNZ, nx0, ny0, nz0, tx, ty, tz);

                        double d2 = ar / 512.0d;
                        double d3 = br / 512.0d;
                        double d5 = (pnr / 10.0d + 1.0d) / 2.0d;
                        double d4 = d5 < 0.0d ? d2 : (d5 > 1.0d ? d3 : d2 + (d3 - d2) * d5);
                        d4 -= 8.0d;
                        d4 += this.islandHeights[dx * size + dz];

                        //pin the top down, exactly like vanilla (fy is the absolute y grid index, i.e. j1)
                        if (fy > 14.0d) {
                            double t = (fy - 14.0d) / 64.0d;
                            if (t > 1.0d) {
                                t = 1.0d;
                            }
                            d4 = d4 * (1.0d - t) + -3000.0d * t;
                        }
                        if (fy < 8.0d) {
                            double t = (8.0d - fy) / 7.0d;
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

    protected static double sampleTrilinear(@NonNull double[] region, int nx, int ny, int nz, int x0, int y0, int z0, double tx, double ty, double tz) {
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
