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
import net.daporkchop.fp2.core.engine.api.server.IFarTileProvider;
import net.daporkchop.fp2.core.engine.Tile;
import net.daporkchop.fp2.core.engine.TileData;
import net.daporkchop.fp2.core.engine.TilePos;
import net.daporkchop.fp2.core.engine.server.gen.rough.AbstractDualContouringRoughVoxelGenerator;
import net.daporkchop.fp2.core.server.world.level.IFarLevelServer;
import net.daporkchop.lib.common.reference.ReferenceStrength;
import net.daporkchop.lib.common.reference.cache.Cached;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;

import static net.daporkchop.fp2.core.engine.EngineConstants.*;

/**
 * Rough voxel generator for the vanilla End ({@code ChunkGeneratorEnd}).
 * <p>
 * Emulates vanilla's island density directly at coarse resolution. There is no water in the End, so both
 * density layers carry the same block density (a flat air layer would produce degenerate zero gradients).
 * Structures (obsidian spikes, chorus plants, end cities/gateways, the central island bedrock fountain)
 * are intentionally ignored - only the end stone body of the islands is reproduced.
 *
 * @author DaPorkchop_
 */
public class EndRoughGenerator1_12 extends AbstractDualContouringRoughVoxelGenerator<EndRoughContext1_12> {
    protected final Cached<EndRoughContext1_12> ctx;

    public EndRoughGenerator1_12(@NonNull IFarLevelServer world, @NonNull IFarTileProvider provider) {
        super(world, provider);

        this.ctx = Cached.threadLocal(() -> new EndRoughContext1_12(this.registry(), (WorldServer) world.implLevel(), CACHE_SIZE), ReferenceStrength.WEAK);
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

        EndRoughContext1_12 ctx = this.ctx.get();
        ctx.init(baseX + (CACHE_MIN << level), baseZ + (CACHE_MIN << level), level);
        double[][] densityMap = this.densityMapCache.get();

        //blocks (both layers carry block density - the End has no liquids)
        ctx.get3d(densityMap[1], baseY + (CACHE_MIN << level));
        System.arraycopy(densityMap[1], 0, densityMap[0], 0, densityMap[1].length);

        //actually create the mesh (using dual contouring)
        this.dualContour(baseX, baseY, baseZ, level, tile, densityMap, ctx);
    }

    @Override
    protected int getFaceState(int blockX, int blockY, int blockZ, int level, double nx, double ny, double nz, double density0, double density1, int edge, int layer, EndRoughContext1_12 ctx) {
        //everything solid in the End is end stone (buildSurfaces is a no-op for our output)
        return this.registry().state2id(Blocks.END_STONE.getDefaultState());
    }

    @Override
    protected void populateVoxelBlockData(int blockX, int blockY, int blockZ, int level, double nx, double ny, double nz, TileData data, EndRoughContext1_12 ctx) {
        //the End has no meaningful sky light falloff for rough purposes, use full brightness
        data.light = BlockLevelConstants.packLight(15, 0);
        data.biome = ctx.getBiome(blockX, blockZ);
    }
}
