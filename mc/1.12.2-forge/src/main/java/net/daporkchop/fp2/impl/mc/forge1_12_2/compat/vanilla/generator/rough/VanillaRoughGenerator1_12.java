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

    /**
     * Проверяет наличие Exact-данных в чанке по всему диапазону Y [0..256),
     * чтобы вертикальные соседи тайлов никогда не расходились во мнениях.
     */
    protected boolean hasExactChunk(FBlockLevel exactWorld, int blockX, int blockZ) {
        return exactWorld.containsAnyData(blockX, 0, blockZ, blockX + 16, 256, blockZ + 16);
    }

    protected void blendExactBoundaries(int baseX, int baseY, int baseZ, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        try (FBlockLevel exactWorld = this.world().exactBlockLevelHolder().worldFor(ExactFBlockLevelHolder.AllowGenerationRequirement.DONT_CARE)) {
            int stoneState = this.registry().state2id(Blocks.STONE.getDefaultState());

            boolean negX = this.hasExactChunk(exactWorld, baseX - 16, baseZ);
            boolean posX = this.hasExactChunk(exactWorld, baseX + 16, baseZ);
            boolean negZ = this.hasExactChunk(exactWorld, baseX, baseZ - 16);
            boolean posZ = this.hasExactChunk(exactWorld, baseX, baseZ + 16);

            boolean negXnegZ = this.hasExactChunk(exactWorld, baseX - 16, baseZ - 16);
            boolean posXnegZ = this.hasExactChunk(exactWorld, baseX + 16, baseZ - 16);
            boolean negXposZ = this.hasExactChunk(exactWorld, baseX - 16, baseZ + 16);
            boolean posXposZ = this.hasExactChunk(exactWorld, baseX + 16, baseZ + 16);

            // В AbstractExactVoxelGenerator узел кэша (nx, ny, nz) всегда сэмплирует мировой блок:
            // (baseX + nx - 1, baseY + ny - 1, baseZ + nz - 1).
            // Для nx, nz в диапазоне [0..16] (17 узлов вдоль грани) мировые координаты блоков лежат в [base - 1 .. base + 15].

            // 1. Граница -X: узлы nx = 0 (и nx = -1), nz = 0..16 -> sampleX = baseX - 1, sampleZ = baseZ + nz - 1
            if (negX) {
                this.sampleBoundaryLineX(exactWorld, baseX - 1, baseX - 1, baseY, baseZ, 0, -1, stoneState, densityMap, ctx);
            }

            // 2. Граница +X: узлы nx = 16, nz = 0..16 -> sampleX = baseX + 15 (fallback baseX + 16), sampleZ = baseZ + nz - 1
            if (posX) {
                this.sampleBoundaryLineX(exactWorld, baseX + 15, baseX + 16, baseY, baseZ, 16, 16, stoneState, densityMap, ctx);
            }

            // 3. Граница -Z: узлы nz = 0 (и nz = -1), nx = 0..16 -> sampleX = baseX + nx - 1, sampleZ = baseZ - 1
            if (negZ) {
                this.sampleBoundaryLineZ(exactWorld, baseX, baseY, baseZ - 1, baseZ - 1, 0, -1, stoneState, densityMap, ctx);
            }

            // 4. Граница +Z: узлы nz = 16, nx = 0..16 -> sampleX = baseX + nx - 1, sampleZ = baseZ + 15 (fallback baseZ + 16)
            if (posZ) {
                this.sampleBoundaryLineZ(exactWorld, baseX, baseY, baseZ + 15, baseZ + 16, 16, 16, stoneState, densityMap, ctx);
            }

            // 5. Четыре угловые колонны (на случай, когда прямые соседи — Rough, но диагональный сосед — Exact,
            // чтобы все 4 сходящихся в углу тайла имели строго одинаковую плотность в общем угловом узле):
            if (!negX && !negZ && negXnegZ) {
                this.sampleCornerColumn(exactWorld, baseX - 1, baseX - 1, baseY, baseZ - 1, baseZ - 1, 0, 0, stoneState, densityMap, ctx);
            }
            if (!posX && !negZ && posXnegZ) {
                this.sampleCornerColumn(exactWorld, baseX + 15, baseX + 16, baseY, baseZ - 1, baseZ - 1, 16, 0, stoneState, densityMap, ctx);
            }
            if (!negX && !posZ && negXposZ) {
                this.sampleCornerColumn(exactWorld, baseX - 1, baseX - 1, baseY, baseZ + 15, baseZ + 16, 0, 16, stoneState, densityMap, ctx);
            }
            if (!posX && !posZ && posXposZ) {
                this.sampleCornerColumn(exactWorld, baseX + 15, baseX + 16, baseY, baseZ + 15, baseZ + 16, 16, 16, stoneState, densityMap, ctx);
            }
        }
    }

    /**
     * Сэмплирует вертикальный срез вдоль оси Z (для фиксированного узла targetX) по правилу sampleZ = baseZ + nz - 1.
     */
    private void sampleBoundaryLineX(
            FBlockLevel exactWorld, int primarySampleX, int fallbackSampleX,
            int baseY, int baseZ, int targetX, int extraTargetX,
            int stoneState, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        int sampleX = primarySampleX;
        boolean loaded = false;
        try {
            // Запрашиваем 16 блоков по Z: для nz = 1..16 это мировые координаты [baseZ .. baseZ + 15]
            exactWorld.multiGetDense(sampleX, baseY + CACHE_MIN - 1, baseZ, 1, CACHE_SIZE, 16, ctx.tempStates, 0, null, 0, null, 0);
            loaded = true;
        } catch (GenerationNotAllowedException e) {
            if (fallbackSampleX != primarySampleX) {
                sampleX = fallbackSampleX;
                try {
                    exactWorld.multiGetDense(sampleX, baseY + CACHE_MIN - 1, baseZ, 1, CACHE_SIZE, 16, ctx.tempStates, 0, null, 0, null, 0);
                    loaded = true;
                } catch (GenerationNotAllowedException ignored) {
                }
            }
        }

        if (!loaded) {
            return;
        }

        for (int dy = 0; dy < CACHE_SIZE; dy++) {
            int sampleY = baseY + CACHE_MIN - 1 + dy;
            int targetY = CACHE_MIN + dy;

            // Узлы nz = 1..16 (соответствуют мировым sampleZ = baseZ .. baseZ + 15)
            for (int nz = 1; nz <= 16; nz++) {
                int dz = nz - 1; // 0..15 в tempStates
                int state;
                if (sampleY < 0) {
                    state = stoneState;
                } else if (sampleY >= 256) {
                    state = 0;
                } else {
                    state = ctx.tempStates[dy * 16 + dz];
                }
                this.setBoundaryDensity(targetX, targetY, nz, state, densityMap, ctx);
                if (extraTargetX != targetX) {
                    this.setBoundaryDensity(extraTargetX, targetY, nz, state, densityMap, ctx);
                }
            }

            // Узел nz = 0 (соответствует мировому sampleZ = baseZ - 1)
            int fallbackZ0 = sampleY < 0 ? stoneState : (sampleY >= 256 ? 0 : ctx.tempStates[dy * 16]);
            int stateZ0 = this.queryBoundaryStateSafe(exactWorld, sampleX, fallbackSampleX, sampleY, baseZ - 1, baseZ, fallbackZ0, stoneState);
            this.setBoundaryDensity(targetX, targetY, 0, stateZ0, densityMap, ctx);
            if (extraTargetX != targetX) {
                this.setBoundaryDensity(extraTargetX, targetY, 0, stateZ0, densityMap, ctx);
            }
        }
    }

    /**
     * Сэмплирует вертикальный срез вдоль оси X (для фиксированного узла targetZ) по правилу sampleX = baseX + nx - 1.
     */
    private void sampleBoundaryLineZ(
            FBlockLevel exactWorld, int baseX, int baseY,
            int primarySampleZ, int fallbackSampleZ, int targetZ, int extraTargetZ,
            int stoneState, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        int sampleZ = primarySampleZ;
        boolean loaded = false;
        try {
            // Запрашиваем 16 блоков по X: для nx = 1..16 это мировые координаты [baseX .. baseX + 15]
            exactWorld.multiGetDense(baseX, baseY + CACHE_MIN - 1, sampleZ, 16, CACHE_SIZE, 1, ctx.tempStates, 0, null, 0, null, 0);
            loaded = true;
        } catch (GenerationNotAllowedException e) {
            if (fallbackSampleZ != primarySampleZ) {
                sampleZ = fallbackSampleZ;
                try {
                    exactWorld.multiGetDense(baseX, baseY + CACHE_MIN - 1, sampleZ, 16, CACHE_SIZE, 1, ctx.tempStates, 0, null, 0, null, 0);
                    loaded = true;
                } catch (GenerationNotAllowedException ignored) {
                }
            }
        }

        if (!loaded) {
            return;
        }

        for (int dy = 0; dy < CACHE_SIZE; dy++) {
            int sampleY = baseY + CACHE_MIN - 1 + dy;
            int targetY = CACHE_MIN + dy;

            // Узлы nx = 1..16 (соответствуют мировым sampleX = baseX .. baseX + 15)
            for (int nx = 1; nx <= 16; nx++) {
                int dx = nx - 1; // 0..15 в tempStates
                int state;
                if (sampleY < 0) {
                    state = stoneState;
                } else if (sampleY >= 256) {
                    state = 0;
                } else {
                    state = ctx.tempStates[dx * CACHE_SIZE + dy];
                }
                this.setBoundaryDensity(nx, targetY, targetZ, state, densityMap, ctx);
                if (extraTargetZ != targetZ) {
                    this.setBoundaryDensity(nx, targetY, extraTargetZ, state, densityMap, ctx);
                }
            }

            // Узел nx = 0 (соответствует мировому sampleX = baseX - 1)
            int fallbackX0 = sampleY < 0 ? stoneState : (sampleY >= 256 ? 0 : ctx.tempStates[dy]);
            int stateX0 = this.queryBoundaryStateSafe(exactWorld, baseX - 1, baseX, sampleY, sampleZ, fallbackSampleZ, fallbackX0, stoneState);
            this.setBoundaryDensity(0, targetY, targetZ, stateX0, densityMap, ctx);
            if (extraTargetZ != targetZ) {
                this.setBoundaryDensity(0, targetY, extraTargetZ, stateX0, densityMap, ctx);
            }
        }
    }

    private void sampleCornerColumn(
            FBlockLevel exactWorld, int primaryX, int fallbackX, int baseY, int primaryZ, int fallbackZ,
            int targetX, int targetZ, int stoneState, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        int sampleX = primaryX;
        int sampleZ = primaryZ;
        boolean loaded = false;
        try {
            exactWorld.multiGetDense(sampleX, baseY + CACHE_MIN - 1, sampleZ, 1, CACHE_SIZE, 1, ctx.tempStates, 0, null, 0, null, 0);
            loaded = true;
        } catch (GenerationNotAllowedException e) {
            if (fallbackX != primaryX || fallbackZ != primaryZ) {
                sampleX = fallbackX;
                sampleZ = fallbackZ;
                try {
                    exactWorld.multiGetDense(sampleX, baseY + CACHE_MIN - 1, sampleZ, 1, CACHE_SIZE, 1, ctx.tempStates, 0, null, 0, null, 0);
                    loaded = true;
                } catch (GenerationNotAllowedException ignored) {
                }
            }
        }

        if (!loaded) {
            return;
        }

        for (int dy = 0; dy < CACHE_SIZE; dy++) {
            int sampleY = baseY + CACHE_MIN - 1 + dy;
            int targetY = CACHE_MIN + dy;
            int state;
            if (sampleY < 0) {
                state = stoneState;
            } else if (sampleY >= 256) {
                state = 0;
            } else {
                state = ctx.tempStates[dy];
            }
            this.setBoundaryDensity(targetX, targetY, targetZ, state, densityMap, ctx);
        }
    }

    private int queryBoundaryStateSafe(
            FBlockLevel exactWorld, int primaryX, int fallbackX, int y, int primaryZ, int fallbackZ,
            int fallbackState, int stoneState) {
        if (y < 0) {
            return stoneState;
        } else if (y >= 256) {
            return 0;
        }
        try {
            return exactWorld.getState(primaryX, y, primaryZ);
        } catch (Exception e1) {
            if (fallbackX != primaryX || fallbackZ != primaryZ) {
                try {
                    return exactWorld.getState(fallbackX, y, fallbackZ);
                } catch (Exception ignored) {
                }
            }
            return fallbackState;
        }
    }

    private void setBoundaryDensity(int x, int y, int z, int state, double[][] densityMap, VanillaRoughContext1_12 ctx) {
        if (x < CACHE_MIN || x >= CACHE_MAX || y < CACHE_MIN || y >= CACHE_MAX || z < CACHE_MIN || z >= CACHE_MAX) {
            return;
        }
        int idx = cacheIndex(x, y, z);
        int worldY = ctx.tileBaseY + y;

        double waterBelowSea = (this.seaLevel() + 1e-4d - worldY);

        double d0, d1;
        IBlockState bs = state == 0 ? null : (IBlockState) this.registry().id2state(state);
        if (bs != null && bs.isOpaqueCube()) {
            d1 = 1.0d;
            d0 = waterBelowSea > 0.0d ? 1.0d : -1.0d;
            if (x >= 0 && x < CACHE_SIZE && y >= 0 && y < CACHE_SIZE && z >= 0 && z < CACHE_SIZE) {
                ctx.boundaryStates[idx] = state;
            }
        } else if (bs != null && bs.getMaterial().isLiquid()) {
            d0 = 1.0d;
            d1 = -1.0d;
        } else {
            d0 = -1.0d;
            d1 = -1.0d;
        }
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
        blockY++;

        int seaLevel = this.seaLevel() >> level << level; //truncate lower bits in order to scale the sea level to the current zoom level
        data.light = BlockLevelConstants.packLight(blockY < seaLevel ? max(15 - (seaLevel - blockY) * 3, 0) : 15, 0);
        data.biome = ctx.getBiome(blockX, blockZ);
    }
}
