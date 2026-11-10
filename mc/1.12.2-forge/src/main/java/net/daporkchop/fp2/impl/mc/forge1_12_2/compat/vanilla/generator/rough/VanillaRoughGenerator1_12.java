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

        if (level == 0) {
            this.blendExactBoundaries(baseX, baseY, baseZ, densityMap, ctx);
        }

        //actually create the mesh (using dual contouring)
        this.dualContour(baseX, baseY, baseZ, level, tile, densityMap, ctx);
    }

    protected void blendExactBoundaries(int baseX, int baseY, int baseZ, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        try (FBlockLevel exactWorld = this.world().exactBlockLevelHolder().worldFor(ExactFBlockLevelHolder.AllowGenerationRequirement.DONT_CARE)) {
            int stoneState = this.registry().state2id(Blocks.STONE.getDefaultState());

            // Boundary -X
            if (exactWorld.containsAnyData(baseX - 16, baseY, baseZ, baseX, baseY + 16, baseZ + 16)) {
                try {
                    exactWorld.multiGetDense(baseX - 1, baseY + CACHE_MIN, baseZ, 1, CACHE_SIZE, 16, ctx.tempStates, 0, null, 0, null, 0);
                    for (int dy = 0; dy < CACHE_SIZE; dy++) {
                        int worldY = baseY + CACHE_MIN + dy;
                        int targetY = CACHE_MIN + dy;
                        for (int dz = 0; dz < 16; dz++) {
                            int queryIdx = dy * 16 + dz;
                            int state = (worldY < 0) ? stoneState : (worldY >= 256 ? 0 : ctx.tempStates[queryIdx]);
                            this.setBoundaryDensity(0, targetY, dz, state, densityMap, ctx);
                            this.setBoundaryDensity(-1, targetY, dz, state, densityMap, ctx);
                        }
                        int state16 = this.queryBoundaryStateSafe(exactWorld, baseX - 1, worldY, baseZ + 16, worldY < 0 ? stoneState : (worldY >= 256 ? 0 : ctx.tempStates[dy * 16 + 15]), stoneState);
                        this.setBoundaryDensity(0, targetY, 16, state16, densityMap, ctx);
                        this.setBoundaryDensity(-1, targetY, 16, state16, densityMap, ctx);
                    }
                } catch (GenerationNotAllowedException ignored) {
                }
            }

            // Boundary +X
            if (exactWorld.containsAnyData(baseX + 16, baseY, baseZ, baseX + 32, baseY + 16, baseZ + 16)) {
                try {
                    exactWorld.multiGetDense(baseX + 16, baseY + CACHE_MIN, baseZ, 1, CACHE_SIZE, 16, ctx.tempStates, 0, null, 0, null, 0);
                    for (int dy = 0; dy < CACHE_SIZE; dy++) {
                        int worldY = baseY + CACHE_MIN + dy;
                        int targetY = CACHE_MIN + dy;
                        for (int dz = 0; dz < 16; dz++) {
                            int queryIdx = dy * 16 + dz;
                            int state = (worldY < 0) ? stoneState : (worldY >= 256 ? 0 : ctx.tempStates[queryIdx]);
                            this.setBoundaryDensity(16, targetY, dz, state, densityMap, ctx);
                        }
                        int state16 = this.queryBoundaryStateSafe(exactWorld, baseX + 16, worldY, baseZ + 16, worldY < 0 ? stoneState : (worldY >= 256 ? 0 : ctx.tempStates[dy * 16 + 15]), stoneState);
                        this.setBoundaryDensity(16, targetY, 16, state16, densityMap, ctx);
                    }
                } catch (GenerationNotAllowedException ignored) {
                }
            }

            // Boundary -Z
            if (exactWorld.containsAnyData(baseX, baseY, baseZ - 16, baseX + 16, baseY + 16, baseZ)) {
                try {
                    exactWorld.multiGetDense(baseX, baseY + CACHE_MIN, baseZ - 1, 16, CACHE_SIZE, 1, ctx.tempStates, 0, null, 0, null, 0);
                    for (int dx = 0; dx < 16; dx++) {
                        for (int dy = 0; dy < CACHE_SIZE; dy++) {
                            int queryIdx = dx * CACHE_SIZE + dy;
                            int worldY = baseY + CACHE_MIN + dy;
                            int targetY = CACHE_MIN + dy;
                            int state = (worldY < 0) ? stoneState : (worldY >= 256 ? 0 : ctx.tempStates[queryIdx]);
                            this.setBoundaryDensity(dx, targetY, 0, state, densityMap, ctx);
                            this.setBoundaryDensity(dx, targetY, -1, state, densityMap, ctx);
                        }
                    }
                    for (int dy = 0; dy < CACHE_SIZE; dy++) {
                        int worldY = baseY + CACHE_MIN + dy;
                        int targetY = CACHE_MIN + dy;
                        int state16 = this.queryBoundaryStateSafe(exactWorld, baseX + 16, worldY, baseZ - 1, worldY < 0 ? stoneState : (worldY >= 256 ? 0 : ctx.tempStates[15 * CACHE_SIZE + dy]), stoneState);
                        this.setBoundaryDensity(16, targetY, 0, state16, densityMap, ctx);
                        this.setBoundaryDensity(16, targetY, -1, state16, densityMap, ctx);
                    }
                } catch (GenerationNotAllowedException ignored) {
                }
            }

            // Boundary +Z
            if (exactWorld.containsAnyData(baseX, baseY, baseZ + 16, baseX + 16, baseY + 16, baseZ + 32)) {
                try {
                    exactWorld.multiGetDense(baseX, baseY + CACHE_MIN, baseZ + 16, 16, CACHE_SIZE, 1, ctx.tempStates, 0, null, 0, null, 0);
                    for (int dx = 0; dx < 16; dx++) {
                        for (int dy = 0; dy < CACHE_SIZE; dy++) {
                            int queryIdx = dx * CACHE_SIZE + dy;
                            int worldY = baseY + CACHE_MIN + dy;
                            int targetY = CACHE_MIN + dy;
                            int state = (worldY < 0) ? stoneState : (worldY >= 256 ? 0 : ctx.tempStates[queryIdx]);
                            this.setBoundaryDensity(dx, targetY, 16, state, densityMap, ctx);
                        }
                    }
                    for (int dy = 0; dy < CACHE_SIZE; dy++) {
                        int worldY = baseY + CACHE_MIN + dy;
                        int targetY = CACHE_MIN + dy;
                        int state16 = this.queryBoundaryStateSafe(exactWorld, baseX + 16, worldY, baseZ + 16, worldY < 0 ? stoneState : (worldY >= 256 ? 0 : ctx.tempStates[15 * CACHE_SIZE + dy]), stoneState);
                        this.setBoundaryDensity(16, targetY, 16, state16, densityMap, ctx);
                    }
                } catch (GenerationNotAllowedException ignored) {
                }
            }
        }
    }

    private int queryBoundaryStateSafe(FBlockLevel exactWorld, int x, int y, int z, int fallbackState, int stoneState) {
        if (y < 0) {
            return stoneState;
        } else if (y >= 256) {
            return 0;
        }
        try {
            return exactWorld.getState(x, y, z);
        } catch (Exception ignored) {
            return fallbackState;
        }
    }

    private void setBoundaryDensity(int x, int y, int z, int state, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        if (x < CACHE_MIN || x >= CACHE_MAX || y < CACHE_MIN || y >= CACHE_MAX || z < CACHE_MIN || z >= CACHE_MAX) {
            return;
        }
        double d0, d1;
        IBlockState bs = state == 0 ? null : (IBlockState) this.registry().id2state(state);
        if (bs != null && bs.isOpaqueCube()) {
            d1 = 1.0d;
            d0 = -1.0d;
            if (x >= 0 && x < CACHE_SIZE && y >= 0 && y < CACHE_SIZE && z >= 0 && z < CACHE_SIZE) {
                ctx.boundaryStates[cacheIndex(x, y, z)] = state;
            }
        } else if (bs != null && bs.getMaterial().isLiquid()) {
            d0 = 1.0d;
            d1 = -1.0d;
        } else {
            d0 = -1.0d;
            d1 = -1.0d;
        }
        int idx = cacheIndex(x, y, z);
        densityMap[0][idx] = d0;
        densityMap[1][idx] = d1;
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
            if (dx >= 0 && dx < 16 && dy >= 0 && dy < 16 && dz >= 0 && dz < 16) {
                int checkX = (dx == 15 && ctx.boundaryStates[cacheIndex(16, dy, dz)] != 0) ? 16 : dx;
                int checkZ = (dz == 15 && ctx.boundaryStates[cacheIndex(checkX, dy, 16)] != 0) ? 16 : dz;
                int bState = ctx.boundaryStates[cacheIndex(checkX, dy, checkZ)];
                if (bState != 0) {
                    IBlockState bs = (IBlockState) this.registry().id2state(bState);
                    if (bs != null && bs.isOpaqueCube()) {
                        return bState;
                    }
                }
            }
        }

        //upward-facing surfaces get the biome's top block (grass/sand/...) so far terrain has roughly correct colors,
        //  steep transitions get filler block (dirt/sandstone/...), and sheer cliffs/overhangs remain stone.
        //the raw density gradient points towards increasing density (downwards into solid ground at a surface),
        //  so an upward-exposed surface has ny < 0 (i.e. -ny > 0).
        if (ny < 0.0d) {
            Biome biome = (Biome) this.registry().id2biome(ctx.getBiome(blockX, blockZ));
            if (biome != null) {
                IBlockState top = ((ATBiome1_12) biome).getTopBlock();
                IBlockState filler = ((ATBiome1_12) biome).getFillerBlock();

                if (edge == 1) { //horizontal surface facing upward
                    if (top != null) {
                        return this.registry().state2id(top);
                    } else if (filler != null) {
                        return this.registry().state2id(filler);
                    }
                } else { //vertical surface (edge == 0 or 2, X or Z axis)
                    double horizSq = nx * nx + nz * nz;
                    double vert = -ny;
                    double vertSq = vert * vert;

                    if (vertSq >= 0.10d * horizSq) { //slopes up to ~72 degrees: normal terrain steps
                        if (top != null) {
                            return this.registry().state2id(top);
                        } else if (filler != null) {
                            return this.registry().state2id(filler);
                        }
                    } else if (vertSq >= 0.03d * horizSq) { //steep transition (~72 to ~80 degrees)
                        if (filler != null) {
                            return this.registry().state2id(filler);
                        } else if (top != null) {
                            return this.registry().state2id(top);
                        }
                    }
                    //steeper than ~80 degrees: sheer vertical cliff, falls through to stone
                }
            }
        }

        return this.registry().state2id(Blocks.STONE.getDefaultState());
    }

    @Override
    protected void populateVoxelBlockData(int blockX, int blockY, int blockZ, int level, double nx, double ny, double nz, TileData data, VanillaRoughContext1_12 ctx) {
        blockY++;

        int seaLevel = this.seaLevel() >> level << level; //truncate lower bits in order to scale the sea level to the current zoom level
        data.light = BlockLevelConstants.packLight(blockY < seaLevel ? max(15 - (seaLevel - blockY) * 3, 0) : 15, 0);
        data.biome = ctx.getBiome(blockX, blockZ);
    }
}
