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

    //vanilla's y lattice has 33 nodes (y indices 0..32)
    protected static final int COLUMN_NODES = 33;

    //lowest detail level for which the raw density field is replaced by a signed height field. at finer levels the
    //  sampling grid still resolves the (strongly non-linear) density profile around the surface, and the raw values
    //  are bit-exact (levels 0..3 sample every vanilla y lattice node), so we keep them there.
    protected static final int HEIGHT_FIELD_LEVEL = 4;

    //reusable bulk noise regions (grid space)
    protected double[] minRegion = new double[0];
    protected double[] maxRegion = new double[0];
    protected double[] mainRegion = new double[0];
    protected int regionNX;
    protected int regionNY;
    protected int regionNZ;
    protected int regionGX0;
    protected int regionGZ0;
    protected int regionHStride; //column spacing in vanilla lattice units (1, or step/4 for level >= GTH_SHIFT)
    protected final double[] colBuf = new double[COLUMN_NODES]; //scratch for a single column of octave noise
    protected double[] columnHeights = new double[0]; //per-column surface heights (height-field mode)

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

        int minBlockX = this.baseX;
        int minBlockY = baseY;
        int minBlockZ = this.baseZ;
        int spanBlocks = (size - 1) * step;

        //Vanilla always samples the min/max/main noises on the native lattice: columns every 4 blocks, y nodes
        //  every 8 blocks, with the y index run starting at 0 (the full run is 5*33*5 nodes per chunk). The y run
        //  MUST start at 0 (and not at the first node we actually need) because NoiseGeneratorImproved keeps a
        //  per-column cache of x-interpolated corner gradients which is only refreshed when floor(y) changes: the
        //  values at a node depend on the entire run prefix. Sampling a shifted or shortened run silently produces
        //  different (wrong) values. Columns are independent of each other, so one vanilla run per column is safe.
        //for coarse levels the raw density field can no longer be resolved by the (very sparse) sampling grid: the
        //  first solid node sits tens or hundreds of blocks below the real surface, and the linear interpolation done by
        //  the dual contouring mesher then places the surface far too high, producing phantom "sheets" in the sky.
        //  instead use a signed height field (surface height minus block Y) so the zero crossing lands exactly on the
        //  per-column surface.
        boolean heightField = level >= HEIGHT_FIELD_LEVEL;

        this.regionGX0 = Math.floorDiv(minBlockX, GTH_SIZE);
        this.regionGZ0 = Math.floorDiv(minBlockZ, GTH_SIZE);
        if (level >= GTH_SHIFT) { //samples are grid-aligned: one column per tile node
            this.regionHStride = step >> GTH_SHIFT;
            this.regionNX = this.regionNZ = size;
        } else { //finer than the vanilla grid: cover the native columns and interpolate
            this.regionHStride = 1;
            this.regionNX = this.regionNZ = (spanBlocks >> GTH_SHIFT) + 2;
        }
        //y run prefix: 0..(highest needed node + 1), capped at the vanilla length. The region must always cover at
        //  least two nodes (nothing can be interpolated on a single node), which can happen for tiles far below y=0.
        //in height-field mode the full column profile is always needed to locate the surface.
        this.regionNY = heightField
                ? COLUMN_NODES
                : Math.max(Math.min(((minBlockY + spanBlocks) >> GTV_SHIFT) + 2, COLUMN_NODES), 2);

        double coordScale = this.settings.coordinateScale;
        double heightScale = this.settings.heightScale;
        //the main noise scales are computed with float arithmetic in vanilla (f / 80.0f etc.), do NOT widen to
        //  double before dividing!
        double mainScaleX = this.settings.coordinateScale / this.settings.mainNoiseScaleX;
        double mainScaleY = this.settings.heightScale / this.settings.mainNoiseScaleY;
        double mainScaleZ = this.settings.coordinateScale / this.settings.mainNoiseScaleZ;

        this.minRegion = ensureCapacity(this.minRegion, this.regionNX * this.regionNZ * this.regionNY);
        this.maxRegion = ensureCapacity(this.maxRegion, this.regionNX * this.regionNZ * this.regionNY);
        this.mainRegion = ensureCapacity(this.mainRegion, this.regionNX * this.regionNZ * this.regionNY);

        for (int ix = 0; ix < this.regionNX; ix++) {
            int gx = this.regionGX0 + ix * this.regionHStride;
            for (int iz = 0; iz < this.regionNZ; iz++) {
                int gz = this.regionGZ0 + iz * this.regionHStride;
                int idx = (ix * this.regionNZ + iz) * this.regionNY;

                this.minLimitNoise.generateNoiseOctaves(this.colBuf, gx, 0, gz, 1, this.regionNY, 1, coordScale, heightScale, coordScale);
                System.arraycopy(this.colBuf, 0, this.minRegion, idx, this.regionNY);
                this.maxLimitNoise.generateNoiseOctaves(this.colBuf, gx, 0, gz, 1, this.regionNY, 1, coordScale, heightScale, coordScale);
                System.arraycopy(this.colBuf, 0, this.maxRegion, idx, this.regionNY);
                this.mainNoise.generateNoiseOctaves(this.colBuf, gx, 0, gz, 1, this.regionNY, 1, mainScaleX, mainScaleY, mainScaleZ);
                System.arraycopy(this.colBuf, 0, this.mainRegion, idx, this.regionNY);
            }
        }

        double upperScale = this.settings.upperLimitScale;
        double lowerScale = this.settings.lowerLimitScale;
        double baseSize = this.settings.baseSize;
        double stretchY = this.settings.stretchY;

        if (heightField) {
            this.fillHeightField(out, minBlockY, level, size, baseSize, stretchY, lowerScale, upperScale);
            return;
        }

        for (int i = 0, dx = 0; dx < size; dx++) {
            int blockX = minBlockX + (dx << level);
            int nx0;
            double tx;
            if (level >= GTH_SHIFT) { //samples coincide with the generated columns
                nx0 = dx;
                tx = 0.0d;
            } else {
                double fx = (double) blockX / GTH_SIZE - this.regionGX0;
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
                double fy = (double) blockY / GTV_SIZE;
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
                    if (level >= GTH_SHIFT) {
                        nz0 = dz;
                        tz = 0.0d;
                    } else {
                        double fz = (double) blockZ / GTH_SIZE - this.regionGZ0;
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

                    //vanilla evaluates the density formula on the native grid nodes (4 blocks apart horizontally, 8
                    //  vertically) and then trilinearly interpolates the *resulting densities* down to block resolution
                    //  (see ChunkGeneratorOverworld.setBlocksInChunk). evaluating the formula at intermediate positions
                    //  instead of the nodes is wrong: the `d1 *= 4.0` branch around d0 must only ever apply to whole nodes,
                    //  otherwise the interpolated surface ends up at the wrong height and can even get split in two.
                    double v000 = this.nodeDensity(nx0, ny0, nz0, baseSize, stretchY, lowerScale, upperScale);
                    double v001 = this.nodeDensity(nx0, ny0, nz0 + 1, baseSize, stretchY, lowerScale, upperScale);
                    double v010 = this.nodeDensity(nx0, ny0 + 1, nz0, baseSize, stretchY, lowerScale, upperScale);
                    double v011 = this.nodeDensity(nx0, ny0 + 1, nz0 + 1, baseSize, stretchY, lowerScale, upperScale);
                    double v100 = this.nodeDensity(nx0 + 1, ny0, nz0, baseSize, stretchY, lowerScale, upperScale);
                    double v101 = this.nodeDensity(nx0 + 1, ny0, nz0 + 1, baseSize, stretchY, lowerScale, upperScale);
                    double v110 = this.nodeDensity(nx0 + 1, ny0 + 1, nz0, baseSize, stretchY, lowerScale, upperScale);
                    double v111 = this.nodeDensity(nx0 + 1, ny0 + 1, nz0 + 1, baseSize, stretchY, lowerScale, upperScale);

                    double v00 = v000 + (v001 - v000) * tz;
                    double v01 = v010 + (v011 - v010) * tz;
                    double v10 = v100 + (v101 - v100) * tz;
                    double v11 = v110 + (v111 - v110) * tz;

                    double v0 = v00 + (v01 - v00) * ty;
                    double v1 = v10 + (v11 - v10) * ty;

                    out[i] = v0 + (v1 - v0) * tx;
                }
            }
        }
    }

    /**
     * Fills the density grid with a signed height field: for every sampled column the vanilla density profile is
     * scanned for its topmost solid node, and the exact surface height is recovered by linearly interpolating the
     * zero crossing between that node and the one above it. The grid value is then {@code surfaceHeight - blockY}, so
     * the dual contouring mesher places the surface exactly at the per-column height regardless of the (coarse) grid
     * spacing. This replaces the raw density on coarse levels only, where the grid is too sparse to resolve the
     * strongly non-linear density profile.
     */
    protected void fillHeightField(@NonNull double[] out, int minBlockY, int level, int size, double baseSize, double stretchY, double lowerScale, double upperScale) {
        double[] heights = this.columnHeights = ensureCapacity(this.columnHeights, size * size);

        int lastNy = this.regionNY - 1;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int ny = lastNy;
                double d0 = this.nodeDensity(dx, ny, dz, baseSize, stretchY, lowerScale, upperScale);
                while (d0 <= 0.0d && ny > 0) {
                    d0 = this.nodeDensity(dx, --ny, dz, baseSize, stretchY, lowerScale, upperScale);
                }

                double h;
                if (d0 <= 0.0d) {
                    h = Double.NEGATIVE_INFINITY; //no solid node anywhere in the column
                } else if (ny == lastNy) {
                    h = (lastNy + 1) * GTV_SIZE;
                } else {
                    double d1 = this.nodeDensity(dx, ny + 1, dz, baseSize, stretchY, lowerScale, upperScale);
                    double t = d0 - d1 > 0.0d ? d0 / (d0 - d1) : 0.0d;
                    h = (ny + t) * GTV_SIZE;
                }
                heights[dx * size + dz] = h;
            }
        }

        for (int i = 0, dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                double blockY = minBlockY + (dy << level);
                for (int dz = 0; dz < size; dz++, i++) {
                    out[i] = heights[dx * size + dz] - blockY;
                }
            }
        }
    }

    /**
     * Computes the vanilla density at a single native grid node. {@code nx} and {@code nz} are indices of columns
     * within the generated noise regions: column {@code i} lies at vanilla lattice index
     * {@code regionGX0 + i * regionHStride} (4-block spacing). {@code ny} is the vanilla y lattice index (0..32),
     * i.e. the block height is {@code ny * GTV_SIZE}.
     * <p>
     * The column-dependent values (biome height/variation and shaped depth noise) are only defined on nodes; when the
     * sample grid is coarser than {@link #GTH_SIZE} blocks (level >= {@link #GTH_SHIFT}) the nodes coincide with the
     * generated columns.
     */
    protected double nodeDensity(int nx, int ny, int nz, double baseSize, double stretchY, double lowerScale, double upperScale) {
        int idx = (nx * this.regionNZ + nz) * this.regionNY + ny;
        double minv = this.minRegion[idx];
        double maxv = this.maxRegion[idx];
        double mainv = this.mainRegion[idx];

        int nodeBlockX = (this.regionGX0 + nx * this.regionHStride) * GTH_SIZE;
        int nodeBlockZ = (this.regionGZ0 + nz * this.regionHStride) * GTH_SIZE;
        int colX = (nodeBlockX - this.baseX) >> this.level;
        int colZ = (nodeBlockZ - this.baseZ) >> this.level;
        if (colX < 0) {
            colX = 0;
        } else if (colX >= this.size) {
            colX = this.size - 1;
        }
        if (colZ < 0) {
            colZ = 0;
        } else if (colZ >= this.size) {
            colZ = this.size - 1;
        }
        int colIdx = colX * this.size + colZ;

        double variation = this.variations[colIdx] * 0.9d + 0.1d;
        double height = (this.heights[colIdx] * 4.0d - 1.0d) / 8.0d;

        double d8 = height + this.depthShaped[colIdx] * 0.2d;
        d8 = d8 * baseSize / 8.0d;
        double d0 = baseSize + d8 * 4.0d;

        double d1 = ((double) ny - d0) * stretchY * 128.0d / 256.0d / variation;
        if (d1 < 0.0d) {
            d1 *= 4.0d;
        }

        double density = clamp01Lerp(minv / lowerScale, maxv / upperScale, (mainv / 10.0d + 1.0d) / 2.0d) - d1;

        //pin the top of the world down, exactly like vanilla does for grid nodes above 29 (y > 232).
        //vanilla computes the factor with float arithmetic: (float)(l1 - 29) / 3.0f
        if (ny > 29) {
            double t = (float) (ny - 29) / 3.0f;
            if (t > 1.0d) {
                t = 1.0d;
            }
            density = density * (1.0d - t) + -10.0d * t;
        }
        return density;
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
}
