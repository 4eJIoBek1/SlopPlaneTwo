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
import net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.biome.weight.BiomeWeightHelper;
import net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.biome.weight.VanillaBiomeWeightHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkGeneratorSettings;
import net.minecraft.world.gen.NoiseGeneratorOctaves;
import net.minecraft.world.gen.NoiseGeneratorPerlin;

import java.util.Random;

import static net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.VanillaTerrainGenConstants1_12.*;
import static net.daporkchop.lib.common.math.PMath.*;
import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * Holds the initialized state for a vanilla {@code ChunkGeneratorOverworld} emulation context.
 * <p>
 * Not thread-safe. Mirrors {@code CWGContext1_12}, but uses vanilla's own {@link NoiseGeneratorOctaves} instances
 * (created with the same seed and in the same order as {@code ChunkGeneratorOverworld} does) instead of reimplementing
 * the noise. This deliberately avoids touching the generator's private fields, at the cost of not picking up noises
 * replaced by other mods through {@code InitNoiseGensEvent} (acceptable for default generation).
 *
 * @author DaPorkchop_
 */
public class VanillaRoughContext1_12 {
    public final int size;
    public final int[] biomes; //fp2 biome ids

    protected final FGameRegistry registry;

    protected final IBiomeProvider biomeProvider;
    protected final BiomeWeightHelper weightHelper;

    protected final ChunkGeneratorSettings settings;

    protected final NoiseGeneratorOctaves minLimitNoise;
    protected final NoiseGeneratorOctaves maxLimitNoise;
    protected final NoiseGeneratorOctaves mainNoise;
    protected final NoiseGeneratorOctaves depthNoise;

    protected final double[] heights; //weighted raw biome heights (settings-space)
    protected final double[] variations; //weighted raw biome variations (settings-space)
    protected final double[] depthShaped; //shaped depth contribution (d7) per column

    //reusable bulk noise regions (grid space)
    protected double[] minRegion = new double[0];
    protected double[] maxRegion = new double[0];
    protected double[] mainRegion = new double[0];
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

    public VanillaRoughContext1_12(@NonNull FGameRegistry registry, @NonNull WorldServer world, int size) {
        this.size = notNegative(size, "size");
        this.biomes = new int[this.size * this.size];

        this.registry = registry;

        String generatorOptions = world.getWorldInfo().getGeneratorOptions();
        this.settings = ChunkGeneratorSettings.Factory.jsonToFactory(generatorOptions).build();

        this.biomeProvider = BiomeHelper.from(world.getBiomeProvider());
        this.weightHelper = new VanillaBiomeWeightHelper(
                this.settings.biomeDepthOffSet, this.settings.biomeDepthWeight,
                this.settings.biomeScaleOffset, this.settings.biomeScaleWeight,
                2);

        //create our own noise generators with the world seed, in EXACTLY the same order as
        //  ChunkGeneratorOverworld's constructor does. The surface/scale/forest generators are unused for density,
        //  but they must still be created to keep the Random sequence identical for depthNoise.
        Random rand = new Random(world.getSeed());
        this.minLimitNoise = new NoiseGeneratorOctaves(rand, 16);
        this.maxLimitNoise = new NoiseGeneratorOctaves(rand, 16);
        this.mainNoise = new NoiseGeneratorOctaves(rand, 8);
        new NoiseGeneratorPerlin(rand, 4); //surfaceNoise (only used by replaceBiomeBlocks, skip output)
        new NoiseGeneratorOctaves(rand, 10); //scaleNoise (unused in 1.12.2 heightmap)
        this.depthNoise = new NoiseGeneratorOctaves(rand, 16);
        new NoiseGeneratorOctaves(rand, 8); //forestNoise (unused in 1.12.2 heightmap)

        this.heights = new double[this.size * this.size];
        this.variations = new double[this.size * this.size];
        this.depthShaped = new double[this.size * this.size];
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

        this.biomeProvider.generateBiomesAndWeightedHeightsVariations(baseX, baseZ, level, this.size, this.biomes, this.heights, this.variations, this.weightHelper);

        //convert biome IDs to fp2 IDs
        for (int i = 0; i < this.size * this.size; i++) {
            this.biomes[i] = this.registry.biome2id(FastRegistry.getBiome(this.biomes[i]));
        }

        //precompute shaped depth noise per column (2D, horizontal only).
        //Vanilla 2D regions are laid out x-major: index = x * sizeZ + z.
        int hStep = 1 << level;
        double[] depthRaw;
        if (level >= GTH_SHIFT) { //samples are grid-aligned: fold the stride into the scale for exact results
            int s = hStep >> GTH_SHIFT;
            depthRaw = this.depthNoise.generateNoiseOctaves(new double[this.size * this.size],
                    Math.floorDiv(baseX, GTH_SIZE) / s, Math.floorDiv(baseZ, GTH_SIZE) / s, this.size, this.size,
                    this.settings.depthNoiseScaleX * s, this.settings.depthNoiseScaleZ * s, this.settings.depthNoiseScaleExponent);
            for (int i = 0; i < this.size * this.size; i++) {
                this.depthShaped[i] = shapeDepth(depthRaw[i]);
            }
        } else { //generate the covering native grid and bilinearly interpolate
            int span = (this.size - 1) * hStep;
            int ngx0 = Math.floorDiv(baseX, GTH_SIZE);
            int ngz0 = Math.floorDiv(baseZ, GTH_SIZE);
            int nnx = (span >> GTH_SHIFT) + 2;
            int nnz = (span >> GTH_SHIFT) + 2;
            depthRaw = this.depthNoise.generateNoiseOctaves(new double[nnx * nnz], ngx0, ngz0, nnx, nnz,
                    this.settings.depthNoiseScaleX, this.settings.depthNoiseScaleZ, this.settings.depthNoiseScaleExponent);
            for (int dx = 0; dx < this.size; dx++) {
                double fx = (double) (baseX + (dx << level)) / GTH_SIZE - ngx0;
                for (int dz = 0; dz < this.size; dz++) {
                    double fz = (double) (baseZ + (dz << level)) / GTH_SIZE - ngz0;
                    this.depthShaped[dx * this.size + dz] = shapeDepth(sampleBilinear(depthRaw, nnx, nnz, fx, fz));
                }
            }
        }
    }

    protected static double shapeDepth(double raw) {
        double d7 = raw / 8000.0d;
        if (d7 < 0.0d) {
            d7 = -d7 * 0.3d;
        }
        d7 = d7 * 3.0d - 2.0d;
        if (d7 < 0.0d) {
            d7 /= 2.0d;
            if (d7 < -1.0d) {
                d7 = -1.0d;
            }
            d7 /= 1.4d;
            d7 /= 2.0d;
        } else {
            if (d7 > 1.0d) {
                d7 = 1.0d;
            }
            d7 /= 8.0d;
        }
        return d7;
    }

    protected static double sampleBilinear(@NonNull double[] region, int nx, int nz, double fx, double fz) {
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = fx - x0;
        double tz = fz - z0;

        if (x0 < 0) {
            x0 = 0;
            tx = 0.0d;
        } else if (x0 >= nx - 1) {
            x0 = nx - 2;
            tx = 1.0d;
        }
        if (z0 < 0) {
            z0 = 0;
            tz = 0.0d;
        } else if (z0 >= nz - 1) {
            z0 = nz - 2;
            tz = 1.0d;
        }

        //x-major layout: index = x * nz + z
        double v00 = region[x0 * nz + z0];
        double v01 = region[x0 * nz + z0 + 1];
        double v10 = region[(x0 + 1) * nz + z0];
        double v11 = region[(x0 + 1) * nz + z0 + 1];

        double v0 = v00 + (v01 - v00) * tz;
        double v1 = v10 + (v11 - v10) * tz;
        return v0 + (v1 - v0) * tx;
    }

    protected int biomeIndex(int x, int z) {
        return ((x - this.baseX) >> this.level) * this.size + ((z - this.baseZ) >> this.level);
    }

    public int getBiome(int x, int z) {
        return this.biomes[this.biomeIndex(x, z)];
    }

    /**
     * Generates a 3d grid of densities.
     * <p>
     * The generated region will be a cube with a side length of {@link #size}, and the lowest point is at
     * ({@link #baseX}, {@code baseY}, {@link #baseZ}) in blocks (including cache padding).
     *
     * @param out   the array to write the generated density values to
     * @param baseY the base Y coordinate (in blocks, already includes cache padding)
     */
    public void get3d(@NonNull double[] out, int baseY) {
        int level = this.level;
        int step = 1 << level;
        int size = this.size;

        //horizontal axes: vanilla grid is 4 blocks (GTH_SIZE). At level >= GTH_SHIFT the samples are grid-aligned
        //  and we can fold the stride into the noise scale for exact results with tiny bulk arrays.
        //vertical axis: vanilla grid is 8 blocks (GTV_SIZE), exact at level >= GTV_SHIFT.
        //Otherwise we generate the covering native grid and trilinearly interpolate (small arrays, only used close up).
        boolean exactH = level >= GTH_SHIFT;
        boolean exactV = level >= GTV_SHIFT;

        int minBlockX = this.baseX;
        int minBlockY = baseY;
        int minBlockZ = this.baseZ;
        int spanBlocks = (size - 1) * step;

        int gx0, nx;
        if (exactH) {
            this.strideX = step >> GTH_SHIFT;
            gx0 = Math.floorDiv(minBlockX, GTH_SIZE);
            nx = size;
            //tile positions are step-aligned, so gx0 is an exact multiple of the stride
            gx0 = gx0 / this.strideX;
            this.strideZ = this.strideX;
            this.regionGX0 = gx0;
            this.regionGZ0 = Math.floorDiv(minBlockZ, GTH_SIZE) / this.strideZ;
            this.regionNX = nx;
            this.regionNZ = nx;
        } else {
            this.strideX = this.strideZ = 1;
            this.regionGX0 = Math.floorDiv(minBlockX, GTH_SIZE);
            this.regionGZ0 = Math.floorDiv(minBlockZ, GTH_SIZE);
            this.regionNX = this.regionNZ = (spanBlocks >> GTH_SHIFT) + 2;
        }

        int gy0, ny;
        if (exactV) {
            this.strideY = step >> GTV_SHIFT;
            gy0 = Math.floorDiv(minBlockY, GTV_SIZE);
            ny = size;
            gy0 = gy0 / this.strideY;
            this.regionGY0 = gy0;
            this.regionNY = ny;
        } else {
            this.strideY = 1;
            this.regionGY0 = Math.floorDiv(minBlockY, GTV_SIZE);
            this.regionNY = (spanBlocks >> GTV_SHIFT) + 2;
        }

        double coordScale = this.settings.coordinateScale;
        double heightScale = this.settings.heightScale;
        this.minRegion = this.minLimitNoise.generateNoiseOctaves(ensureCapacity(this.minRegion, this.regionNX * this.regionNY * this.regionNZ),
                this.regionGX0, this.regionGY0, this.regionGZ0, this.regionNX, this.regionNY, this.regionNZ,
                coordScale * this.strideX, heightScale * this.strideY, coordScale * this.strideZ);
        this.maxRegion = this.maxLimitNoise.generateNoiseOctaves(ensureCapacity(this.maxRegion, this.regionNX * this.regionNY * this.regionNZ),
                this.regionGX0, this.regionGY0, this.regionGZ0, this.regionNX, this.regionNY, this.regionNZ,
                coordScale * this.strideX, heightScale * this.strideY, coordScale * this.strideZ);
        this.mainRegion = this.mainNoise.generateNoiseOctaves(ensureCapacity(this.mainRegion, this.regionNX * this.regionNY * this.regionNZ),
                this.regionGX0, this.regionGY0, this.regionGZ0, this.regionNX, this.regionNY, this.regionNZ,
                coordScale / this.settings.mainNoiseScaleX * this.strideX,
                heightScale / this.settings.mainNoiseScaleY * this.strideY,
                coordScale / this.settings.mainNoiseScaleZ * this.strideZ);

        double upperScale = this.settings.upperLimitScale;
        double lowerScale = this.settings.lowerLimitScale;
        double baseSize = this.settings.baseSize;
        double stretchY = this.settings.stretchY;

        for (int i = 0, dx = 0; dx < size; dx++) {
            int blockX = minBlockX + (dx << level);
            double gridX = (double) blockX / GTH_SIZE;
            double fx = exactH ? dx : (gridX - this.regionGX0);
            for (int dy = 0; dy < size; dy++) {
                int blockY = minBlockY + (dy << level);
                double gridY = (double) blockY / GTV_SIZE;
                double fy = exactV ? dy : (gridY - this.regionGY0);
                for (int dz = 0; dz < size; dz++, i++) {
                    int blockZ = minBlockZ + (dz << level);
                    double gridZ = (double) blockZ / GTH_SIZE;
                    double fz = exactH ? dz : (gridZ - this.regionGZ0);

                    double minv = sampleTrilinear(this.minRegion, this.regionNX, this.regionNY, this.regionNZ, fx, fy, fz);
                    double maxv = sampleTrilinear(this.maxRegion, this.regionNX, this.regionNY, this.regionNZ, fx, fy, fz);
                    double mainv = sampleTrilinear(this.mainRegion, this.regionNX, this.regionNY, this.regionNZ, fx, fy, fz);

                    int colIdx = dx * size + dz;
                    double variation = this.variations[colIdx] * 0.9d + 0.1d;
                    double height = (this.heights[colIdx] * 4.0d - 1.0d) / 8.0d;

                    double d8 = height + this.depthShaped[colIdx] * 0.2d;
                    d8 = d8 * baseSize / 8.0d;
                    double d0 = baseSize + d8 * 4.0d;

                    double d1 = (gridY - d0) * stretchY * 128.0d / 256.0d / variation;
                    if (d1 < 0.0d) {
                        d1 *= 4.0d;
                    }

                    double d2 = minv / lowerScale;
                    double d3 = maxv / upperScale;
                    double d4 = (mainv / 10.0d + 1.0d) / 2.0d;
                    double density = clamp01Lerp(d2, d3, d4) - d1;

                    //pin the top of the world down, exactly like vanilla does for grid nodes above 29 (y > 232)
                    double gridYAbs = (double) blockY / GTV_SIZE;
                    if (gridYAbs > 29.0d) {
                        double t = (gridYAbs - 29.0d) / 3.0d;
                        if (t > 1.0d) {
                            t = 1.0d;
                        }
                        density = density * (1.0d - t) + -10.0d * t;
                    }

                    out[i] = density;
                }
            }
        }
    }

    protected static double[] ensureCapacity(double[] arr, int length) {
        return arr.length >= length ? arr : new double[length];
    }

    protected static double clamp01Lerp(double lo, double hi, double t) {
        //MathHelper.denormalizeClamp
        if (t < 0.0d) {
            t = 0.0d;
        } else if (t > 1.0d) {
            t = 1.0d;
        }
        return lo + (hi - lo) * t;
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
