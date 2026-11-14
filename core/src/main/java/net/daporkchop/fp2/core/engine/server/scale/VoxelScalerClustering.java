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

package net.daporkchop.fp2.core.engine.server.scale;

import com.google.common.collect.ImmutableList;
import lombok.NonNull;
import net.daporkchop.fp2.core.engine.Tile;
import net.daporkchop.fp2.core.engine.TileData;
import net.daporkchop.fp2.core.engine.TilePos;
import net.daporkchop.fp2.core.engine.api.server.IFarTileProvider;
import net.daporkchop.fp2.core.engine.api.server.gen.IFarScaler;
import net.daporkchop.fp2.core.engine.server.gen.AbstractVoxelGenerator;
import net.daporkchop.fp2.core.engine.util.TilePosArrayList;
import net.daporkchop.fp2.core.server.world.level.IFarLevelServer;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static net.daporkchop.fp2.api.world.level.BlockLevelConstants.*;
import static net.daporkchop.fp2.core.engine.EngineConstants.*;
import static net.daporkchop.lib.common.math.PMath.clamp;
import static net.daporkchop.lib.common.util.PValidation.checkArg;

/**
 * Fast zero-allocation LOD voxel scaler based on Rossignac-Borrel Vertex Clustering.
 *
 * @author DaPorkchop_
 */
public class VoxelScalerClustering extends AbstractVoxelGenerator implements IFarScaler {
    public static final int SRC_MIN = -4;
    public static final int SRC_MAX = (T_VOXELS << 1) + 4; // 36
    public static final int SRC_SIZE = SRC_MAX - SRC_MIN; // 40

    public static final int DST_MIN = -1;
    public static final int DST_MAX = T_VOXELS + 1; // 17
    public static final int DST_SIZE = DST_MAX - DST_MIN; // 18
    public static final int TOTAL_DST_CELLS = DST_SIZE * DST_SIZE * DST_SIZE; // 5832

    protected static int srcTileIndex(int x, int y, int z) {
        final int fac = (((SRC_MAX - 1) >> T_SHIFT) + 1) - (SRC_MIN >> T_SHIFT);
        final int add = -(SRC_MIN >> T_SHIFT) << T_SHIFT;
        return (((x + add) >> T_SHIFT) * fac + ((y + add) >> T_SHIFT)) * fac + ((z + add) >> T_SHIFT);
    }

    protected static int dstIndex(int x, int y, int z) {
        return ((x - DST_MIN) * DST_SIZE + (y - DST_MIN)) * DST_SIZE + (z - DST_MIN);
    }

    protected static class WorkerContext {
        final int[] clusterSumX = new int[TOTAL_DST_CELLS];
        final int[] clusterSumY = new int[TOTAL_DST_CELLS];
        final int[] clusterSumZ = new int[TOTAL_DST_CELLS];
        final int[] clusterCount = new int[TOTAL_DST_CELLS];
        final int[] dstEdges = new int[TOTAL_DST_CELLS];
        final int[] dstStates = new int[TOTAL_DST_CELLS * EDGE_COUNT];
        final int[] dstBiomeAndLight = new int[TOTAL_DST_CELLS];
        final BitSet dstVoxels = new BitSet(TOTAL_DST_CELLS);
        final TileData data = new TileData();

        void reset() {
            Arrays.fill(this.clusterSumX, 0);
            Arrays.fill(this.clusterSumY, 0);
            Arrays.fill(this.clusterSumZ, 0);
            Arrays.fill(this.clusterCount, 0);
            Arrays.fill(this.dstEdges, 0);
            Arrays.fill(this.dstStates, 0);
            Arrays.fill(this.dstBiomeAndLight, 0);
            this.dstVoxels.clear();
            this.data.reset();
        }
    }

    protected final ThreadLocal<WorkerContext> contextRef = ThreadLocal.withInitial(WorkerContext::new);

    public VoxelScalerClustering(@NonNull IFarLevelServer world, @NonNull IFarTileProvider provider) {
        super(world, provider);
    }

    @Override
    public List<TilePos> outputs(@NonNull TilePos srcPos) {
        int x = srcPos.x();
        int y = srcPos.y();
        int z = srcPos.z();
        int targetLevel = srcPos.level() + 1;

        int x0 = (x & 1) == 0 ? (x >> 1) - 1 : (x >> 1);
        int x1 = (x & 1) == 0 ? (x >> 1) : (x >> 1) + 1;
        int y0 = (y & 1) == 0 ? (y >> 1) - 1 : (y >> 1);
        int y1 = (y & 1) == 0 ? (y >> 1) : (y >> 1) + 1;
        int z0 = (z & 1) == 0 ? (z >> 1) - 1 : (z >> 1);
        int z1 = (z & 1) == 0 ? (z >> 1) : (z >> 1) + 1;

        List<TilePos> out = new TilePosArrayList(8);
        for (int dx = x0; dx <= x1; dx++) {
            for (int dy = y0; dy <= y1; dy++) {
                for (int dz = z0; dz <= z1; dz++) {
                    out.add(new TilePos(targetLevel, dx, dy, dz));
                }
            }
        }
        return out;
    }

    @Override
    public List<TilePos> inputs(@NonNull TilePos dstPos) {
        checkArg(dstPos.level() > 0, "cannot generate inputs for level 0!");

        int x = dstPos.x() << 1;
        int y = dstPos.y() << 1;
        int z = dstPos.z() << 1;
        int level = dstPos.level() - 1;

        final int min = SRC_MIN >> T_SHIFT;
        final int max = ((SRC_MAX - 1) >> T_SHIFT) + 1;
        List<TilePos> out = new TilePosArrayList((max - min) * (max - min) * (max - min));
        for (int dx = min; dx < max; dx++) {
            for (int dy = min; dy < max; dy++) {
                for (int dz = min; dz < max; dz++) {
                    out.add(new TilePos(level, x + dx, y + dy, z + dz));
                }
            }
        }
        return out;
    }

    @Override
    public long scale(@NonNull Tile[] srcTiles, @NonNull Tile dst) {
        WorkerContext ctx = this.contextRef.get();
        ctx.reset();

        int[] clusterSumX = ctx.clusterSumX;
        int[] clusterSumY = ctx.clusterSumY;
        int[] clusterSumZ = ctx.clusterSumZ;
        int[] clusterCount = ctx.clusterCount;
        int[] dstEdges = ctx.dstEdges;
        int[] dstStates = ctx.dstStates;
        int[] dstBiomeAndLight = ctx.dstBiomeAndLight;
        BitSet dstVoxels = ctx.dstVoxels;
        TileData data = ctx.data;

        // Pass 1: Scan all fine voxels from source tiles
        for (int x = SRC_MIN; x < SRC_MAX; x++) {
            for (int y = SRC_MIN; y < SRC_MAX; y++) {
                for (int z = SRC_MIN; z < SRC_MAX; z++) {
                    Tile srcTile = srcTiles[srcTileIndex(x, y, z)];
                    if (srcTile == null || !srcTile.get(x & T_MASK, y & T_MASK, z & T_MASK, data)) {
                        continue;
                    }

                    int cx = x >> 1;
                    int cy = y >> 1;
                    int cz = z >> 1;
                    if (cx < DST_MIN || cx >= DST_MAX || cy < DST_MIN || cy >= DST_MAX || cz < DST_MIN || cz >= DST_MAX) {
                        continue;
                    }

                    int di = dstIndex(cx, cy, cz);

                    // Accumulate centroid position
                    int offX = ((x & 1) << POS_FRACT_SHIFT) + data.x;
                    int offY = ((y & 1) << POS_FRACT_SHIFT) + data.y;
                    int offZ = ((z & 1) << POS_FRACT_SHIFT) + data.z;

                    clusterSumX[di] += offX;
                    clusterSumY[di] += offY;
                    clusterSumZ[di] += offZ;
                    clusterCount[di]++;

                    // Accumulate edges & states
                    int edges = data.edges;
                    if (edges != 0) {
                        for (int edge = 0; edge < EDGE_COUNT; edge++) {
                            int dir = (edges >> (edge << 1)) & EDGE_DIR_MASK;
                            if (dir != EDGE_DIR_NONE) {
                                dstEdges[di] |= (dir << (edge << 1));
                                int state = data.states[edge];
                                if (state == 0) {
                                    //a face without a block state would be baked with the air texture, i.e. turn into a hole. this
                                    //  shouldn't happen with data produced by the generators, but the reference QEF scaler guards
                                    //  against it as well, and a hole in the mesh is a really nasty thing to debug
                                    state = 1;
                                }

                                int stateIdx = di * EDGE_COUNT + edge;
                                int existingState = dstStates[stateIdx];
                                if (existingState == 0) {
                                    dstStates[stateIdx] = state;
                                    dstBiomeAndLight[di] = (data.biome << 8) | (data.light & 0xFF);
                                } else {
                                    byte existingType = this.extendedStateRegistryData().type(existingState);
                                    byte newType = this.extendedStateRegistryData().type(state);
                                    // OPAQUE always takes precedence over non-opaque (prevents leaves from erasing solid ground).
                                    //  this also covers the water/seabed collision on high detail levels: a water surface and the
                                    //  ground below it share a single edge, and keeping the water would drop the ground from the
                                    //  mesh entirely (the sky would show through the translucent water). keeping the solid block
                                    //  makes the water surface disappear from the coarse mesh instead, which is far less visible.
                                    if (newType == BLOCK_TYPE_OPAQUE || existingType != BLOCK_TYPE_OPAQUE) {
                                        dstStates[stateIdx] = state;
                                        dstBiomeAndLight[di] = (data.biome << 8) | (data.light & 0xFF);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pass 2: Propagate required vertices for all active quads
        for (int x = DST_MIN; x < DST_MAX - 1; x++) {
            for (int y = DST_MIN; y < DST_MAX - 1; y++) {
                for (int z = DST_MIN; z < DST_MAX - 1; z++) {
                    int di = dstIndex(x, y, z);
                    int edges = dstEdges[di];
                    if (edges == 0) {
                        continue;
                    }

                    for (int edge = 0; edge < EDGE_COUNT; edge++) {
                        if (((edges >> (edge << 1)) & EDGE_DIR_MASK) != EDGE_DIR_NONE) {
                            for (int i = 0; i < CONNECTION_INDEX_COUNT; i++) {
                                int c = CONNECTION_INDICES[edge * CONNECTION_INDEX_COUNT + i];
                                int vx = x + ((c >> 2) & 1);
                                int vy = y + ((c >> 1) & 1);
                                int vz = z + (c & 1);
                                int vIndex = dstIndex(vx, vy, vz);
                                dstVoxels.set(vIndex);

                                // If this vertex has no biome/light yet, inherit from the originating face
                                if (dstBiomeAndLight[vIndex] == 0) {
                                    dstBiomeAndLight[vIndex] = dstBiomeAndLight[di];
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pass 3: Populate destination tile
        for (int x = 0; x < T_VOXELS; x++) {
            for (int y = 0; y < T_VOXELS; y++) {
                for (int z = 0; z < T_VOXELS; z++) {
                    int di = dstIndex(x, y, z);
                    if (!dstVoxels.get(di)) {
                        continue;
                    }

                    int count = clusterCount[di];
                    if (count > 0) {
                        // Average and scale down by 2: (sum / count) / 2 = (sum + count) / (2 * count)
                        data.x = clamp((clusterSumX[di] + count) / (count << 1), 0, POS_ONE);
                        data.y = clamp((clusterSumY[di] + count) / (count << 1), 0, POS_ONE);
                        data.z = clamp((clusterSumZ[di] + count) / (count << 1), 0, POS_ONE);
                    } else {
                        data.x = POS_ONE >> 1;
                        data.y = POS_ONE >> 1;
                        data.z = POS_ONE >> 1;
                    }

                    data.edges = dstEdges[di];
                    data.states[0] = dstStates[di * EDGE_COUNT + 0];
                    data.states[1] = dstStates[di * EDGE_COUNT + 1];
                    data.states[2] = dstStates[di * EDGE_COUNT + 2];

                    int bl = dstBiomeAndLight[di];
                    data.biome = (bl >> 8) & 0xFF;
                    data.light = (byte) bl;

                    dst.set(x, y, z, data);
                }
            }
        }

        return 0L;
    }
}
