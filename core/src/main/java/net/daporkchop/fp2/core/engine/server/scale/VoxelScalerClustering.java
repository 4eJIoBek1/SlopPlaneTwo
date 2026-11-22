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

import lombok.NonNull;
import net.daporkchop.fp2.api.world.registry.FExtendedStateRegistryData;
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
 * Fast zero-allocation LOD voxel scaler that telescopes collinear sub-edges along the coarse grid and
 * places vertices via coarse-edge surface-net crossings.
 *
 * @author DaPorkchop_
 */
public class VoxelScalerClustering extends AbstractVoxelGenerator implements IFarScaler {
    public static final int SRC_MIN = -2;
    public static final int SRC_MAX = T_VOXELS << 1; // 32
    public static final int SRC_SIZE = SRC_MAX - SRC_MIN; // 34

    public static final int DST_MIN = -1;
    public static final int DST_MAX = T_VOXELS + 1; // 17
    public static final int DST_SIZE = DST_MAX - DST_MIN; // 18
    public static final int TOTAL_DST_CELLS = DST_SIZE * DST_SIZE * DST_SIZE; // 5832

    protected static final int EDGE_GRID_MAX = T_VOXELS; // 16 (active coarse edges: -1..15)
    protected static final int TOTAL_SUB_EDGE_SLOTS = TOTAL_DST_CELLS * EDGE_COUNT * 2;

    protected static int srcTileIndex(int x, int y, int z) {
        final int fac = (((SRC_MAX - 1) >> T_SHIFT) + 1) - (SRC_MIN >> T_SHIFT);
        final int add = -(SRC_MIN >> T_SHIFT) << T_SHIFT;
        return (((x + add) >> T_SHIFT) * fac + ((y + add) >> T_SHIFT)) * fac + ((z + add) >> T_SHIFT);
    }

    protected static int dstIndex(int x, int y, int z) {
        return ((x - DST_MIN) * DST_SIZE + (y - DST_MIN)) * DST_SIZE + (z - DST_MIN);
    }

    protected static class WorkerContext {
        // Per coarse edge two collinear sub-edges (slot0 = half 0, slot1 = half 1), split by water vs solid/other
        // so overhanging leaves or bridges never cancel out continuous water sheets beneath them.
        final byte[] solidDir = new byte[TOTAL_SUB_EDGE_SLOTS];
        final byte[] waterDir = new byte[TOTAL_SUB_EDGE_SLOTS];
        final int[] solidState = new int[TOTAL_SUB_EDGE_SLOTS];
        final int[] waterState = new int[TOTAL_SUB_EDGE_SLOTS];
        final byte[] solidPos = new byte[TOTAL_SUB_EDGE_SLOTS];
        final byte[] waterPos = new byte[TOTAL_SUB_EDGE_SLOTS];
        final int[] solidBiomeLight = new int[TOTAL_SUB_EDGE_SLOTS];
        final int[] waterBiomeLight = new int[TOTAL_SUB_EDGE_SLOTS];

        final int[] dstEdges = new int[TOTAL_DST_CELLS];
        final int[] dstStates = new int[TOTAL_DST_CELLS * EDGE_COUNT];
        final int[] dstBiomeAndLight = new int[TOTAL_DST_CELLS];

        // Vertex placement accumulators from active coarse edge crossings (Surface Nets mass point)
        final int[] vertSumX = new int[TOTAL_DST_CELLS];
        final int[] vertSumY = new int[TOTAL_DST_CELLS];
        final int[] vertSumZ = new int[TOTAL_DST_CELLS];
        final int[] vertCount = new int[TOTAL_DST_CELLS];
        final int[] waterVertYSum = new int[TOTAL_DST_CELLS];
        final int[] waterVertYCount = new int[TOTAL_DST_CELLS];
        final short[] vertRank = new short[TOTAL_DST_CELLS];
        final BitSet dstVoxels = new BitSet(TOTAL_DST_CELLS);
        final TileData data = new TileData();

        void reset() {
            Arrays.fill(this.solidDir, (byte) 0);
            Arrays.fill(this.waterDir, (byte) 0);
            Arrays.fill(this.dstEdges, 0);
            Arrays.fill(this.dstStates, 0);
            Arrays.fill(this.dstBiomeAndLight, 0);
            Arrays.fill(this.vertSumX, 0);
            Arrays.fill(this.vertSumY, 0);
            Arrays.fill(this.vertSumZ, 0);
            Arrays.fill(this.vertCount, 0);
            Arrays.fill(this.waterVertYSum, 0);
            Arrays.fill(this.waterVertYCount, 0);
            Arrays.fill(this.vertRank, Short.MIN_VALUE);
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

        int x0 = x >> 1;
        int x1 = (x & 1) != 0 ? x0 + 1 : x0;
        int y0 = y >> 1;
        int y1 = (y & 1) != 0 ? y0 + 1 : y0;
        int z0 = z >> 1;
        int z1 = (z & 1) != 0 ? z0 + 1 : z0;

        List<TilePos> out = new TilePosArrayList((x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1));
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

        FExtendedStateRegistryData stateData = this.extendedStateRegistryData();
        TileData data = ctx.data;

        // Pass 1: Collect only fine edges lying on coarse grid edge lines (one active sub-voxel write per slot)
        for (int tx = -1; tx <= 1; tx++) {
            for (int ty = -1; ty <= 1; ty++) {
                for (int tz = -1; tz <= 1; tz++) {
                    Tile srcTile = srcTiles[(tx + 1) * 9 + (ty + 1) * 3 + (tz + 1)];
                    if (srcTile == null) {
                        continue;
                    }
                    int count = srcTile.count();
                    for (int j = 0; j < count; j++) {
                        int voxelPos = srcTile.get(j, data);
                        int edges = data.edges;
                        if (edges == 0) {
                            continue;
                        }
                        int fx = (tx << T_SHIFT) + ((voxelPos >> (T_SHIFT << 1)) & T_MASK);
                        int fy = (ty << T_SHIFT) + ((voxelPos >> T_SHIFT) & T_MASK);
                        int fz = (tz << T_SHIFT) + (voxelPos & T_MASK);
                        if (fx < SRC_MIN || fy < SRC_MIN || fz < SRC_MIN) {
                            continue;
                        }

                        int ox = fx & 1;
                        int oy = fy & 1;
                        int oz = fz & 1;
                        if (ox + oy + oz < 2) {
                            continue;
                        }

                        int di = dstIndex(fx >> 1, fy >> 1, fz >> 1);
                        int bl = (data.biome << 8) | (data.light & 0xFF);

                        if ((oy & oz) != 0) {
                            int dir = edges & EDGE_DIR_MASK;
                            if (dir != EDGE_DIR_NONE) {
                                recordSubEdge(ctx, stateData, di, 0, ox, dir, data.states[0], data.x, bl);
                            }
                        }
                        if ((ox & oz) != 0) {
                            int dir = (edges >> 2) & EDGE_DIR_MASK;
                            if (dir != EDGE_DIR_NONE) {
                                recordSubEdge(ctx, stateData, di, 1, oy, dir, data.states[1], data.y, bl);
                            }
                        }
                        if ((ox & oy) != 0) {
                            int dir = (edges >> 4) & EDGE_DIR_MASK;
                            if (dir != EDGE_DIR_NONE) {
                                recordSubEdge(ctx, stateData, di, 2, oz, dir, data.states[2], data.z, bl);
                            }
                        }
                    }
                }
            }
        }

        // Pass 2: Telescope each coarse edge cleanly (eliminates EDGE_DIR_BOTH plates and cross-planes) and
        // scatter exact crossing positions to corner voxels (eliminates empty-corner (0.5,0.5,0.5) spikes).
        for (int x = DST_MIN; x < EDGE_GRID_MAX; x++) {
            for (int y = DST_MIN; y < EDGE_GRID_MAX; y++) {
                for (int z = DST_MIN; z < EDGE_GRID_MAX; z++) {
                    int di = dstIndex(x, y, z);
                    int outEdges = 0;

                    for (int edge = 0; edge < EDGE_COUNT; edge++) {
                        int si = di * EDGE_COUNT + edge;
                        int slot0 = si << 1;
                        int slot1 = slot0 | 1;
                        int sNet = ctx.solidDir[slot0] + ctx.solidDir[slot1];
                        int wNet = ctx.waterDir[slot0] + ctx.waterDir[slot1];

                        // Preserve top surface on flat worlds / bottom-of-world slabs when bottom void meets top at y=0
                        if (sNet == 0 && edge == 1 && y == 0 && ctx.solidDir[slot0] < 0 && ctx.solidDir[slot1] > 0) {
                            int belowIdx = (((2 * x + 1) >> T_SHIFT) + 1) * 9 + (((2 * z + 1) >> T_SHIFT) + 1);
                            if (srcTiles[belowIdx] == null) {
                                sNet = 1;
                            }
                        }
                        if (sNet == 0 && wNet == 0) {
                            continue;
                        }

                        int finalDir;
                        int finalState;
                        int finalT;
                        int finalBL;
                        boolean isEdgeWater;

                        if (wNet == 0) {
                            finalDir = sNet > 0 ? EDGE_DIR_POSITIVE : EDGE_DIR_NEGATIVE;
                            int win = pickSolidSlot(ctx.solidDir, slot0, slot1, sNet);
                            finalState = ctx.solidState[win];
                            finalT = ctx.solidPos[win];
                            finalBL = ctx.solidBiomeLight[win];
                            isEdgeWater = false;
                        } else if (sNet == 0) {
                            finalDir = wNet > 0 ? EDGE_DIR_POSITIVE : EDGE_DIR_NEGATIVE;
                            int win = ctx.waterDir[slot1] != 0 ? slot1 : slot0;
                            finalState = ctx.waterState[win];
                            finalT = ctx.waterPos[win];
                            finalBL = ctx.waterBiomeLight[win];
                            isEdgeWater = true;
                        } else {
                            // If both layers crossed: keep water under overhanging foliage/bridges on Y-edges so water stays
                            // hole-free, while letting opaque seabeds beat water when both transition in the same direction.
                            boolean waterUnderOverhang = (sNet > 0) != (wNet > 0) && edge == 1 && wNet > 0 && sNet < 0;
                            if (waterUnderOverhang) {
                                finalDir = EDGE_DIR_POSITIVE;
                                int win = ctx.waterDir[slot1] != 0 ? slot1 : slot0;
                                finalState = ctx.waterState[win];
                                finalT = ctx.waterPos[win];
                                finalBL = ctx.waterBiomeLight[win];
                                isEdgeWater = true;
                            } else {
                                finalDir = sNet > 0 ? EDGE_DIR_POSITIVE : EDGE_DIR_NEGATIVE;
                                int win = pickSolidSlot(ctx.solidDir, slot0, slot1, sNet);
                                finalState = ctx.solidState[win];
                                finalT = ctx.solidPos[win];
                                finalBL = ctx.solidBiomeLight[win];
                                isEdgeWater = false;
                            }
                        }

                        outEdges |= (finalDir << (edge << 1));
                        ctx.dstStates[si] = finalState;

                        int sky = unpackSkyLight((byte) finalBL);
                        short rank = (short) ((edge == 1 ? 1024 : 0) + (!isEdgeWater ? 512 : 0) + (sky << 4) + (y + 2));

                        for (int i = 0; i < CONNECTION_INDEX_COUNT; i++) {
                            int c = CONNECTION_INDICES[edge * CONNECTION_INDEX_COUNT + i];
                            int dx = (c >> 2) & 1;
                            int dy = (c >> 1) & 1;
                            int dz = c & 1;
                            int vx = x + dx;
                            int vy = y + dy;
                            int vz = z + dz;
                            if (vx < 0 || vx >= T_VOXELS || vy < 0 || vy >= T_VOXELS || vz < 0 || vz >= T_VOXELS) {
                                continue;
                            }
                            int vIdx = dstIndex(vx, vy, vz);
                            ctx.dstVoxels.set(vIdx);

                            int lx;
                            int ly;
                            int lz;
                            if (edge == 0) {
                                lx = finalT;
                                ly = (1 - dy) << POS_FRACT_SHIFT;
                                lz = (1 - dz) << POS_FRACT_SHIFT;
                            } else if (edge == 1) {
                                lx = (1 - dx) << POS_FRACT_SHIFT;
                                ly = finalT;
                                lz = (1 - dz) << POS_FRACT_SHIFT;
                            } else {
                                lx = (1 - dx) << POS_FRACT_SHIFT;
                                ly = (1 - dy) << POS_FRACT_SHIFT;
                                lz = finalT;
                            }

                            ctx.vertSumX[vIdx] += lx;
                            ctx.vertSumY[vIdx] += ly;
                            ctx.vertSumZ[vIdx] += lz;
                            ctx.vertCount[vIdx]++;
                            if (isEdgeWater) {
                                // Pin any vertex touching a water quad to water level so underwater seabeds never pull water into spikes
                                ctx.waterVertYSum[vIdx] += ly;
                                ctx.waterVertYCount[vIdx]++;
                            }
                            if (rank > ctx.vertRank[vIdx]) {
                                ctx.vertRank[vIdx] = rank;
                                ctx.dstBiomeAndLight[vIdx] = finalBL;
                            }
                        }
                    }

                    ctx.dstEdges[di] = outEdges;
                }
            }
        }

        // Pass 3: Populate destination tile
        for (int x = 0; x < T_VOXELS; x++) {
            for (int y = 0; y < T_VOXELS; y++) {
                for (int z = 0; z < T_VOXELS; z++) {
                    int di = dstIndex(x, y, z);
                    if (!ctx.dstVoxels.get(di)) {
                        continue;
                    }

                    int cnt = ctx.vertCount[di];
                    if (cnt > 0) {
                        data.x = clamp((ctx.vertSumX[di] + (cnt >> 1)) / cnt, 0, POS_ONE);
                        int wCnt = ctx.waterVertYCount[di];
                        data.y = wCnt > 0
                                ? clamp((ctx.waterVertYSum[di] + (wCnt >> 1)) / wCnt, 0, POS_ONE)
                                : clamp((ctx.vertSumY[di] + (cnt >> 1)) / cnt, 0, POS_ONE);
                        data.z = clamp((ctx.vertSumZ[di] + (cnt >> 1)) / cnt, 0, POS_ONE);
                    } else {
                        data.x = POS_ONE >> 1;
                        data.y = POS_ONE >> 1;
                        data.z = POS_ONE >> 1;
                    }

                    data.edges = ctx.dstEdges[di];
                    data.states[0] = ctx.dstStates[di * EDGE_COUNT + 0];
                    data.states[1] = ctx.dstStates[di * EDGE_COUNT + 1];
                    data.states[2] = ctx.dstStates[di * EDGE_COUNT + 2];

                    int bl = ctx.dstBiomeAndLight[di];
                    data.biome = bl >>> 8;
                    data.light = (byte) bl;

                    dst.set(x, y, z, data);
                }
            }
        }

        return 0L;
    }

    private static void recordSubEdge(
            WorkerContext ctx, FExtendedStateRegistryData stateData,
            int di, int edge, int half, int dir, int state, int finePos, int bl) {
        int signed = (dir & 1) - ((dir >> 1) & 1);
        if (signed == 0) {
            return;
        }
        if (state == 0) {
            state = 1;
        }
        int slot = ((di * EDGE_COUNT + edge) << 1) | half;
        int coarseT = ((half << POS_FRACT_SHIFT) + clamp(finePos, 0, POS_ONE)) >> 1;
        if (stateData.isWater(state)) {
            ctx.waterDir[slot] = (byte) signed;
            ctx.waterState[slot] = state;
            ctx.waterPos[slot] = (byte) coarseT;
            ctx.waterBiomeLight[slot] = bl;
        } else {
            ctx.solidDir[slot] = (byte) signed;
            ctx.solidState[slot] = state;
            ctx.solidPos[slot] = (byte) coarseT;
            ctx.solidBiomeLight[slot] = bl;
        }
    }

    private static int pickSolidSlot(byte[] solidDir, int slot0, int slot1, int net) {
        int d0 = solidDir[slot0];
        int d1 = solidDir[slot1];
        if (d0 != 0 && d1 == 0) {
            return slot0;
        }
        if (d1 != 0 && d0 == 0) {
            return slot1;
        }
        return net > 0 ? slot1 : slot0;
    }
}
