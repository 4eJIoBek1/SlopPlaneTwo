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
import net.daporkchop.fp2.api.world.level.BlockLevelConstants;
import net.daporkchop.fp2.api.world.level.FBlockLevel;
import net.daporkchop.fp2.api.world.level.GenerationNotAllowedException;
import net.daporkchop.fp2.core.engine.api.server.IFarTileProvider;
import net.daporkchop.fp2.core.engine.Tile;
import net.daporkchop.fp2.core.engine.TileData;
import net.daporkchop.fp2.core.engine.TilePos;
import net.daporkchop.fp2.core.engine.server.gen.rough.AbstractDualContouringRoughVoxelGenerator;
import net.daporkchop.fp2.core.server.world.ExactFBlockLevelHolder;
import net.daporkchop.fp2.core.server.world.level.IFarLevelServer;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.at.world.biome.ATBiome1_12;
import net.daporkchop.lib.common.reference.ReferenceStrength;
import net.daporkchop.lib.common.reference.cache.Cached;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;

import java.util.Arrays;

import static java.lang.Math.*;
import static net.daporkchop.fp2.api.world.level.BlockLevelConstants.*;
import static net.daporkchop.fp2.core.engine.EngineConstants.*;

/**
 * Rough voxel generator for the default vanilla overworld ({@code ChunkGeneratorOverworld}).
 * <p>
 * Emulates vanilla's heightmap density directly at coarse resolution (like {@code CWGVoxelGenerator1_12} does for
 * CubicWorldGen): structures, caves, trees and per-biome surface replacement are intentionally ignored, only the
 * base stone shape, water level and top-block coloring are reproduced.
 *
 * @author DaPorkchop_
 */
public class VanillaRoughGenerator1_12 extends AbstractDualContouringRoughVoxelGenerator<VanillaRoughContext1_12> {
    protected final Cached<VanillaRoughContext1_12> ctx;

    public VanillaRoughGenerator1_12(@NonNull IFarLevelServer world, @NonNull IFarTileProvider provider) {
        super(world, provider);

        this.ctx = Cached.threadLocal(() -> new VanillaRoughContext1_12(this.registry(), (WorldServer) world.implLevel(), CACHE_SIZE), ReferenceStrength.WEAK);
    }

    @Override
    public boolean canGenerate(@NonNull TilePos pos) {
        return true;
    }

    @Override
    public void generate(@NonNull TilePos pos, @NonNull Tile tile) {
        int level = pos.level();
        int baseX = pos.minBlockX();
        int baseY = pos.minBlockY();
        int baseZ = pos.minBlockZ();

        VanillaRoughContext1_12 ctx = this.ctx.get();
        ctx.tileBaseX = baseX;
        ctx.tileBaseY = baseY;
        ctx.tileBaseZ = baseZ;
        ctx.resetBoundary();

        ctx.init(baseX + (CACHE_MIN << level), baseZ + (CACHE_MIN << level), level);
        double[][] densityMap = this.densityMapCache.get();

        //water
        assert cacheIndex(0, 0, 1) - cacheIndex(0, 0, 0) == 1 : "cache coordinate order must be z-minor";

        double scaleFactor = 1.0d / (1 << level);
        for (int x = CACHE_MIN; x < CACHE_MAX; x++) {
            for (int y = CACHE_MIN; y < CACHE_MAX; y++) {
                final int idx = cacheIndex(x, y, CACHE_MIN);
                Arrays.fill(densityMap[0], idx, idx + CACHE_SIZE, (this.seaLevel() + 1e-4d - (baseY + (y << level))) * scaleFactor);
            }
        }

        //blocks
        ctx.get3d(densityMap[1], baseY + (CACHE_MIN << level));

        int exactBoundaries = 0;
        if (level == 0) {
            exactBoundaries = this.blendExactBoundaries(baseX, baseY, baseZ, densityMap, ctx);
        }

        //actually create the mesh (using dual contouring)
        this.dualContour(baseX, baseY, baseZ, level, tile, densityMap, exactBoundaries, ctx);
    }

    protected int blendExactBoundaries(int baseX, int baseY, int baseZ, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        int exactBoundaries = 0;
        try (FBlockLevel exactWorld = this.world().exactBlockLevelHolder().worldFor(ExactFBlockLevelHolder.AllowGenerationRequirement.DONT_CARE)) {
            int stoneState = this.registry().state2id(Blocks.STONE.getDefaultState());

            // Boundary -X
            if (exactWorld.containsAnyData(baseX - 16, baseY, baseZ, baseX, baseY + 16, baseZ + 16)) {
                try {
                    exactWorld.multiGetDense(baseX - 1, baseY + CACHE_MIN, baseZ, 1, CACHE_SIZE, 16, ctx.tempStates, 0, ctx.tempBiomes, 0, ctx.tempLights, 0);
                    exactBoundaries |= BOUNDARY_NEG_X;
                    for (int dy = 0; dy < CACHE_SIZE; dy++) {
                        int worldY = baseY + CACHE_MIN + dy;
                        int targetY = CACHE_MIN + dy;
                        for (int dz = 0; dz < 16; dz++) {
                            int queryIdx = dy * 16 + dz;
                            int state, biome;
                            byte light;
                            if (worldY < 0) {
                                state = stoneState;
                                biome = ctx.getBiome(baseX - 1, baseZ + dz);
                                light = 0;
                            } else if (worldY >= 256) {
                                state = 0;
                                biome = ctx.getBiome(baseX - 1, baseZ + dz);
                                light = (byte) 0xFF;
                            } else {
                                state = ctx.tempStates[queryIdx];
                                biome = ctx.tempBiomes[queryIdx];
                                light = ctx.tempLights[queryIdx];
                            }
                            double d0, d1;
                            int type = state == 0 ? BLOCK_TYPE_INVISIBLE : this.extendedStateRegistryData().type(state);
                            if (type == BLOCK_TYPE_OPAQUE) {
                                d1 = 1.0d;
                                d0 = -1.0d;
                            } else if (type == BLOCK_TYPE_TRANSPARENT) {
                                IBlockState bs = (IBlockState) this.registry().id2state(state);
                                if (bs.getMaterial().isLiquid()) {
                                    d0 = 1.0d;
                                    d1 = -1.0d;
                                } else {
                                    d1 = 1.0d;
                                    d0 = -1.0d;
                                }
                            } else {
                                d0 = -1.0d;
                                d1 = -1.0d;
                            }
                            for (int x = -1; x <= 0; x++) {
                                int idx = cacheIndex(x, targetY, dz);
                                densityMap[0][idx] = d0;
                                densityMap[1][idx] = d1;
                                if (state != 0) {
                                    ctx.boundaryStates[idx] = state;
                                    ctx.boundaryBiomes[idx] = biome;
                                    ctx.boundaryLights[idx] = light;
                                }
                            }
                        }
                        for (int x = -1; x <= 0; x++) {
                            copyHaloZ(x, targetY, densityMap, ctx);
                        }
                    }
                } catch (GenerationNotAllowedException ignored) {
                }
            }

            // Boundary +X
            if (exactWorld.containsAnyData(baseX + 16, baseY, baseZ, baseX + 32, baseY + 16, baseZ + 16)) {
                try {
                    exactWorld.multiGetDense(baseX + 16, baseY + CACHE_MIN, baseZ, 1, CACHE_SIZE, 16, ctx.tempStates, 0, ctx.tempBiomes, 0, ctx.tempLights, 0);
                    exactBoundaries |= BOUNDARY_POS_X;
                    for (int dy = 0; dy < CACHE_SIZE; dy++) {
                        int worldY = baseY + CACHE_MIN + dy;
                        int targetY = CACHE_MIN + dy;
                        for (int dz = 0; dz < 16; dz++) {
                            int queryIdx = dy * 16 + dz;
                            int state, biome;
                            byte light;
                            if (worldY < 0) {
                                state = stoneState;
                                biome = ctx.getBiome(baseX + 16, baseZ + dz);
                                light = 0;
                            } else if (worldY >= 256) {
                                state = 0;
                                biome = ctx.getBiome(baseX + 16, baseZ + dz);
                                light = (byte) 0xFF;
                            } else {
                                state = ctx.tempStates[queryIdx];
                                biome = ctx.tempBiomes[queryIdx];
                                light = ctx.tempLights[queryIdx];
                            }
                            double d0, d1;
                            int type = state == 0 ? BLOCK_TYPE_INVISIBLE : this.extendedStateRegistryData().type(state);
                            if (type == BLOCK_TYPE_OPAQUE) {
                                d1 = 1.0d;
                                d0 = -1.0d;
                            } else if (type == BLOCK_TYPE_TRANSPARENT) {
                                IBlockState bs = (IBlockState) this.registry().id2state(state);
                                if (bs.getMaterial().isLiquid()) {
                                    d0 = 1.0d;
                                    d1 = -1.0d;
                                } else {
                                    d1 = 1.0d;
                                    d0 = -1.0d;
                                }
                            } else {
                                d0 = -1.0d;
                                d1 = -1.0d;
                            }
                            int idx = cacheIndex(16, targetY, dz);
                            densityMap[0][idx] = d0;
                            densityMap[1][idx] = d1;
                            if (state != 0) {
                                ctx.boundaryStates[idx] = state;
                                ctx.boundaryBiomes[idx] = biome;
                                ctx.boundaryLights[idx] = light;
                            }
                        }
                        copyHaloZ(16, targetY, densityMap, ctx);
                    }
                } catch (GenerationNotAllowedException ignored) {
                }
            }

            // Boundary -Z
            if (exactWorld.containsAnyData(baseX, baseY, baseZ - 16, baseX + 16, baseY + 16, baseZ)) {
                try {
                    exactWorld.multiGetDense(baseX, baseY + CACHE_MIN, baseZ - 1, 16, CACHE_SIZE, 1, ctx.tempStates, 0, ctx.tempBiomes, 0, ctx.tempLights, 0);
                    exactBoundaries |= BOUNDARY_NEG_Z;
                    for (int dx = 0; dx < 16; dx++) {
                        for (int dy = 0; dy < CACHE_SIZE; dy++) {
                            int queryIdx = dx * CACHE_SIZE + dy;
                            int worldY = baseY + CACHE_MIN + dy;
                            int targetY = CACHE_MIN + dy;
                            int state, biome;
                            byte light;
                            if (worldY < 0) {
                                state = stoneState;
                                biome = ctx.getBiome(baseX + dx, baseZ - 1);
                                light = 0;
                            } else if (worldY >= 256) {
                                state = 0;
                                biome = ctx.getBiome(baseX + dx, baseZ - 1);
                                light = (byte) 0xFF;
                            } else {
                                state = ctx.tempStates[queryIdx];
                                biome = ctx.tempBiomes[queryIdx];
                                light = ctx.tempLights[queryIdx];
                            }
                            double d0, d1;
                            int type = state == 0 ? BLOCK_TYPE_INVISIBLE : this.extendedStateRegistryData().type(state);
                            if (type == BLOCK_TYPE_OPAQUE) {
                                d1 = 1.0d;
                                d0 = -1.0d;
                            } else if (type == BLOCK_TYPE_TRANSPARENT) {
                                IBlockState bs = (IBlockState) this.registry().id2state(state);
                                if (bs.getMaterial().isLiquid()) {
                                    d0 = 1.0d;
                                    d1 = -1.0d;
                                } else {
                                    d1 = 1.0d;
                                    d0 = -1.0d;
                                }
                            } else {
                                d0 = -1.0d;
                                d1 = -1.0d;
                            }
                            for (int z = -1; z <= 0; z++) {
                                int idx = cacheIndex(dx, targetY, z);
                                densityMap[0][idx] = d0;
                                densityMap[1][idx] = d1;
                                if (state != 0) {
                                    ctx.boundaryStates[idx] = state;
                                    ctx.boundaryBiomes[idx] = biome;
                                    ctx.boundaryLights[idx] = light;
                                }
                            }
                        }
                    }
                    for (int dy = 0; dy < CACHE_SIZE; dy++) {
                        int targetY = CACHE_MIN + dy;
                        for (int z = -1; z <= 0; z++) {
                            copyHaloX(z, targetY, densityMap, ctx);
                        }
                    }
                } catch (GenerationNotAllowedException ignored) {
                }
            }

            // Boundary +Z
            if (exactWorld.containsAnyData(baseX, baseY, baseZ + 16, baseX + 16, baseY + 16, baseZ + 32)) {
                try {
                    exactWorld.multiGetDense(baseX, baseY + CACHE_MIN, baseZ + 16, 16, CACHE_SIZE, 1, ctx.tempStates, 0, ctx.tempBiomes, 0, ctx.tempLights, 0);
                    exactBoundaries |= BOUNDARY_POS_Z;
                    for (int dx = 0; dx < 16; dx++) {
                        for (int dy = 0; dy < CACHE_SIZE; dy++) {
                            int queryIdx = dx * CACHE_SIZE + dy;
                            int worldY = baseY + CACHE_MIN + dy;
                            int targetY = CACHE_MIN + dy;
                            int state, biome;
                            byte light;
                            if (worldY < 0) {
                                state = stoneState;
                                biome = ctx.getBiome(baseX + dx, baseZ + 16);
                                light = 0;
                            } else if (worldY >= 256) {
                                state = 0;
                                biome = ctx.getBiome(baseX + dx, baseZ + 16);
                                light = (byte) 0xFF;
                            } else {
                                state = ctx.tempStates[queryIdx];
                                biome = ctx.tempBiomes[queryIdx];
                                light = ctx.tempLights[queryIdx];
                            }
                            double d0, d1;
                            int type = state == 0 ? BLOCK_TYPE_INVISIBLE : this.extendedStateRegistryData().type(state);
                            if (type == BLOCK_TYPE_OPAQUE) {
                                d1 = 1.0d;
                                d0 = -1.0d;
                            } else if (type == BLOCK_TYPE_TRANSPARENT) {
                                IBlockState bs = (IBlockState) this.registry().id2state(state);
                                if (bs.getMaterial().isLiquid()) {
                                    d0 = 1.0d;
                                    d1 = -1.0d;
                                } else {
                                    d1 = 1.0d;
                                    d0 = -1.0d;
                                }
                            } else {
                                d0 = -1.0d;
                                d1 = -1.0d;
                            }
                            int idx = cacheIndex(dx, targetY, 16);
                            densityMap[0][idx] = d0;
                            densityMap[1][idx] = d1;
                            if (state != 0) {
                                ctx.boundaryStates[idx] = state;
                                ctx.boundaryBiomes[idx] = biome;
                                ctx.boundaryLights[idx] = light;
                            }
                        }
                    }
                    for (int dy = 0; dy < CACHE_SIZE; dy++) {
                        int targetY = CACHE_MIN + dy;
                        copyHaloX(16, targetY, densityMap, ctx);
                    }
                } catch (GenerationNotAllowedException ignored) {
                }
            }
        }
        return exactBoundaries;
    }

    private static void copyHaloZ(int x, int targetY, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        int idx0 = cacheIndex(x, targetY, 0);
        int idxNeg1 = cacheIndex(x, targetY, -1);
        densityMap[0][idxNeg1] = densityMap[0][idx0];
        densityMap[1][idxNeg1] = densityMap[1][idx0];
        ctx.boundaryStates[idxNeg1] = ctx.boundaryStates[idx0];
        ctx.boundaryBiomes[idxNeg1] = ctx.boundaryBiomes[idx0];
        ctx.boundaryLights[idxNeg1] = ctx.boundaryLights[idx0];

        int idx15 = cacheIndex(x, targetY, 15);
        int idx16 = cacheIndex(x, targetY, 16);
        densityMap[0][idx16] = densityMap[0][idx15];
        densityMap[1][idx16] = densityMap[1][idx15];
        ctx.boundaryStates[idx16] = ctx.boundaryStates[idx15];
        ctx.boundaryBiomes[idx16] = ctx.boundaryBiomes[idx15];
        ctx.boundaryLights[idx16] = ctx.boundaryLights[idx15];
    }

    private static void copyHaloX(int z, int targetY, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        int idx0 = cacheIndex(0, targetY, z);
        int idxNeg1 = cacheIndex(-1, targetY, z);
        densityMap[0][idxNeg1] = densityMap[0][idx0];
        densityMap[1][idxNeg1] = densityMap[1][idx0];
        ctx.boundaryStates[idxNeg1] = ctx.boundaryStates[idx0];
        ctx.boundaryBiomes[idxNeg1] = ctx.boundaryBiomes[idx0];
        ctx.boundaryLights[idxNeg1] = ctx.boundaryLights[idx0];

        int idx15 = cacheIndex(15, targetY, z);
        int idx16 = cacheIndex(16, targetY, z);
        densityMap[0][idx16] = densityMap[0][idx15];
        densityMap[1][idx16] = densityMap[1][idx15];
        ctx.boundaryStates[idx16] = ctx.boundaryStates[idx15];
        ctx.boundaryBiomes[idx16] = ctx.boundaryBiomes[idx15];
        ctx.boundaryLights[idx16] = ctx.boundaryLights[idx15];
    }

    @Override
    protected int getFaceState(int blockX, int blockY, int blockZ, int level, double nx, double ny, double nz, double density0, double density1, int edge, int layer, VanillaRoughContext1_12 ctx) {
        if (layer == 0) { //layer 0 is always water lol
            return this.registry().state2id(Blocks.WATER.getDefaultState());
        }

        if (level == 0) {
            int dx = blockX - ctx.tileBaseX;
            int dy = blockY - ctx.tileBaseY;
            int dz = blockZ - ctx.tileBaseZ;
            if (dx >= CACHE_MIN && dx < CACHE_MAX && dy >= CACHE_MIN && dy < CACHE_MAX && dz >= CACHE_MIN && dz < CACHE_MAX) {
                int bState = ctx.boundaryStates[cacheIndex(dx, dy, dz)];
                if (bState != 0) {
                    return bState;
                }
                if (dx == 15) {
                    bState = ctx.boundaryStates[cacheIndex(16, dy, dz)];
                    if (bState != 0) {
                        return bState;
                    }
                }
                if (dz == 15) {
                    bState = ctx.boundaryStates[cacheIndex(dx, dy, 16)];
                    if (bState != 0) {
                        return bState;
                    }
                }
            }
        }

        //upward-facing surfaces get the biome's top block (grass/sand/...) so far terrain has roughly correct colors,
        //  everything else is plain stone (we don't emulate replaceBiomeBlocks in detail).
        //edge is the face axis (0=X, 1=Y, 2=Z); the raw density gradient points towards increasing density (downwards
        //  at a top surface), so an upward-facing face is a Y-axis face with ny < 0.
        if (edge == 1 && ny < 0.0d) {
            Biome biome = (Biome) this.registry().id2biome(ctx.getBiome(blockX, blockZ));
            if (biome != null) {
                IBlockState top = ((ATBiome1_12) biome).getTopBlock();
                if (top != null) {
                    return this.registry().state2id(top);
                }
            }
        }

        return this.registry().state2id(Blocks.STONE.getDefaultState());
    }

    @Override
    protected void populateVoxelBlockData(int blockX, int blockY, int blockZ, int level, double nx, double ny, double nz, TileData data, VanillaRoughContext1_12 ctx) {
        if (level == 0) {
            int dx = blockX - ctx.tileBaseX;
            int dy = blockY - ctx.tileBaseY;
            int dz = blockZ - ctx.tileBaseZ;
            if (dx >= CACHE_MIN && dx < CACHE_MAX && dy >= CACHE_MIN && dy < CACHE_MAX && dz >= CACHE_MIN && dz < CACHE_MAX) {
                int idx = cacheIndex(dx, dy, dz);
                if (ctx.boundaryStates[idx] != 0) {
                    data.biome = ctx.boundaryBiomes[idx];
                    data.light = ctx.boundaryLights[idx];
                    return;
                }
                if (dx == 15 && ctx.boundaryStates[cacheIndex(16, dy, dz)] != 0) {
                    int idx16 = cacheIndex(16, dy, dz);
                    data.biome = ctx.boundaryBiomes[idx16];
                    data.light = ctx.boundaryLights[idx16];
                    return;
                }
                if (dz == 15 && ctx.boundaryStates[cacheIndex(dx, dy, 16)] != 0) {
                    int idx16 = cacheIndex(dx, dy, 16);
                    data.biome = ctx.boundaryBiomes[idx16];
                    data.light = ctx.boundaryLights[idx16];
                    return;
                }
            }
        }

        blockY++;

        int seaLevel = this.seaLevel() >> level << level; //truncate lower bits in order to scale the sea level to the current zoom level
        data.light = BlockLevelConstants.packLight(blockY < seaLevel ? max(15 - (seaLevel - blockY) * 3, 0) : 15, 0);
        data.biome = ctx.getBiome(blockX, blockZ);
    }
}
