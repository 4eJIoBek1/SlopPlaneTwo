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

package net.daporkchop.fp2.core.engine.client.bake;

import lombok.NonNull;
import lombok.val;
import net.daporkchop.fp2.api.world.level.BlockLevelConstants;
import net.daporkchop.fp2.core.client.render.LevelRenderer;
import net.daporkchop.fp2.core.client.render.TextureUVs;
import net.daporkchop.fp2.core.engine.DirectTilePosAccess;
import net.daporkchop.fp2.core.engine.Tile;
import net.daporkchop.fp2.core.engine.TileData;
import net.daporkchop.fp2.core.engine.TilePos;
import net.daporkchop.fp2.core.engine.api.ctx.IFarClientContext;
import net.daporkchop.fp2.core.engine.client.RenderConstants;
import net.daporkchop.fp2.core.engine.client.struct.VoxelLocalAttributes;
import net.daporkchop.fp2.core.engine.util.TilePosSingleLevelAABBSet;
import net.daporkchop.fp2.core.util.GlobalAllocators;
import net.daporkchop.fp2.gl.attribute.AttributeWriter;
import net.daporkchop.fp2.gl.draw.DrawMode;
import net.daporkchop.fp2.gl.draw.index.IndexWriter;
import net.daporkchop.lib.common.pool.array.ArrayAllocator;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static net.daporkchop.fp2.core.engine.EngineConstants.*;
import static net.daporkchop.fp2.core.util.math.MathUtil.*;

/**
 * Shared code for baking voxel geometry.
 *
 * @author DaPorkchop_
 */
public class VoxelBaker implements IRenderBaker<VoxelLocalAttributes> {
    protected static final int COARSE_SEAM_SEARCH_RADIUS = 2;

    protected static int vertexMapIndex(int dx, int dy, int dz, int i, int edge) {
        int j = CONNECTION_INDICES[i];
        int ddx = dx + ((j >> 2) & 1);
        int ddy = dy + ((j >> 1) & 1);
        int ddz = dz + (j & 1);

        return ((ddx * T_VERTS + ddy) * T_VERTS + ddz) * EDGE_COUNT + edge;
    }

    protected final LevelRenderer levelRenderer;
    protected final TextureUVs textureUVs;
    protected final boolean forceBlockyMesh;
    protected final boolean useTriangles;

    public VoxelBaker(@NonNull IFarClientContext context, @NonNull DrawMode drawMode) {
        this.levelRenderer = context.level().renderer();
        this.textureUVs = this.levelRenderer.textureUVs();
        this.forceBlockyMesh = context.fp2().globalConfig().quality().forceBlockyMesh();

        switch (drawMode) {
            case QUADS:
            case TRIANGLES:
                this.useTriangles = drawMode == DrawMode.TRIANGLES;
                break;
            default:
                throw new IllegalArgumentException("Unsupported draw mode: " + drawMode);
        }
    }

    @Override
    public Set<TilePos> bakeOutputs(@NonNull TilePos srcPos) {
        int x = srcPos.x();
        int y = srcPos.y();
        int z = srcPos.z();
        int level = srcPos.level();

        return TilePosSingleLevelAABBSet.createInclusive(
                new TilePos(level, x - 1, y - 1, z - 1),
                new TilePos(level, x, level == 0 ? y : y + 1, z));
    }

    @Override
    public List<TilePos> bakeInputs(@NonNull TilePos dstPos) {
        int x = dstPos.x();
        int y = dstPos.y();
        int z = dstPos.z();
        int level = dstPos.level();

        val res = DirectTilePosAccess.newPositionArrayList();
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    res.add(new TilePos(level, x + dx, y + dy, z + dz));
                }
            }
        }
        if (level >= 1) {
            //also include the row of horizontal neighbors directly underneath (dy = -1) so coarse seam bridging
            //  near tile Y-boundaries (e.g. Y = 64) can still locate a neighbor surface within +-2 voxels
            res.add(new TilePos(level, x, y - 1, z));
            res.add(new TilePos(level, x, y - 1, z + 1));
            res.add(new TilePos(level, x + 1, y - 1, z));
            res.add(new TilePos(level, x + 1, y - 1, z + 1));
        }
        return res;
    }

    @Override
    public void bake(@NonNull TilePos pos, @NonNull Tile[] srcs, @NonNull BakeOutput<VoxelLocalAttributes> output) {
        if (srcs[0] == null) {
            return;
        }

        ArrayAllocator<int[]> alloc = GlobalAllocators.ALLOC_INT.get();
        int[] map = alloc.atLeast(cb(T_VERTS) * EDGE_COUNT);
        Arrays.fill(map, 0, cb(T_VERTS) * EDGE_COUNT, -1);

        try {
            //step 1: write vertices for all source tiles, and assign indices
            this.writeVertices(srcs, pos.minBlockX(), pos.minBlockY(), pos.minBlockZ(), pos.level(), map, output.verts);

            //step 2: write indices to actually connect the vertices and build the mesh
            this.writeIndices(srcs, srcs[0], pos.minBlockX(), pos.minBlockY(), pos.minBlockZ(), pos.level(), map, output.verts, output.indicesPerPass);
        } finally {
            alloc.release(map);
        }
    }

    protected void writeVertices(Tile[] srcs, int blockX, int blockY, int blockZ, int level, int[] map, AttributeWriter<VoxelLocalAttributes> verts) {
        final TileData data = new TileData();

        for (int i = 0; i < 8; i++) {
            Tile src = srcs[i];
            if (src == null) {
                continue;
            }

            int maxDx = CONNECTION_INTERSECTION_VOLUMES[i * 3 + 0];
            int maxDy = CONNECTION_INTERSECTION_VOLUMES[i * 3 + 1];
            int maxDz = CONNECTION_INTERSECTION_VOLUMES[i * 3 + 2];
            for (int dx = 0; dx < maxDx; dx++) {
                for (int dy = 0; dy < maxDy; dy++) {
                    for (int dz = 0; dz < maxDz; dz++) {
                        if (!src.get(dx, dy, dz, data)) {
                            continue;
                        }

                        this.writeVertex(blockX, blockY, blockZ, level, dx + (((i >> 2) & 1) << T_SHIFT), dy + (((i >> 1) & 1) << T_SHIFT), dz + ((i & 1) << T_SHIFT), data, verts, map);
                    }
                }
            }
        }
    }

    protected void writeVertex(int baseX, int baseY, int baseZ, int level, int x, int y, int z, TileData data, AttributeWriter<VoxelLocalAttributes> vertices, int[] map) {
        baseX += (x & T_VOXELS) << level;
        baseY += (y & T_VOXELS) << level;
        baseZ += (z & T_VOXELS) << level;

        int baseMapIndex = ((x * T_VERTS + y) * T_VERTS + z) * 3;

        final int blockX = baseX + ((x & ~(x & T_VOXELS)) << level);
        final int blockY = baseY + ((y & ~(y & T_VOXELS)) << level);
        final int blockZ = baseZ + ((z & ~(z & T_VOXELS)) << level);

        final int fractMask = this.forceBlockyMesh ? 0 : -1;

        int blockLight = BlockLevelConstants.unpackBlockLight(data.light);
        int skyLight = BlockLevelConstants.unpackSkyLight(data.light);
        //sky and block light are one unsigned byte each, and are interpreted as normalized floats. since the lightmap texture is 16x16, using
        //  the upper 4 bits for the regular light level (which is in range [0,15]) results in a float range of [0,15/16]. additionally, we set
        //  the bottom 4 bits to 0b1000. this is equivalent to an offset of 0.5 texels, which prevents blending artifacts when sampling right along
        //  the edge of a texture.
        vertices.append()
                .light((blockLight << 4) | 8, (skyLight << 4) | 8)
                .pos((x << POS_FRACT_SHIFT) + (data.x & fractMask), (y << POS_FRACT_SHIFT) + (data.y & fractMask), (z << POS_FRACT_SHIFT) + (data.z & fractMask))
                .close();

        boolean first = true;
        EDGES:
        for (int edge = 0; edge < EDGE_COUNT; edge++) {
            for (int j = 0; j < edge; j++) {
                if (data.states[j] == data.states[edge]) { //states match, don't duplicate vertex data for this edge
                    map[baseMapIndex + edge] = map[baseMapIndex + j];
                    continue EDGES;
                }
            }

            if (!first) {
                vertices.appendCopy(vertices.size() - 1).close();
            }

            vertices.back()
                    .state(this.textureUVs.state2index(data.states[edge]))
                    .color(this.levelRenderer.tintFactorForStateInBiomeAtPos(data.states[edge], data.biome, blockX, blockY, blockZ))
                    .close();

            map[baseMapIndex + edge] = vertices.size() - 1;
            first = false;
        }
    }

    protected void writeIndices(Tile[] srcs, Tile src, int baseX, int baseY, int baseZ, int level, int[] map, AttributeWriter<VoxelLocalAttributes> verts, IndexWriter[] indices) {
        final TileData data = new TileData();
        final TileData probe = new TileData();

        for (int j = 0; j < src.count(); j++) {
            int voxelPos = src.get(j, data);
            int dx = (voxelPos >> (T_SHIFT << 1)) & T_MASK;
            int dy = (voxelPos >> T_SHIFT) & T_MASK;
            int dz = voxelPos & T_MASK;

            int edges = data.edges;
            if ((((edges >> 2) ^ (edges >> 3)) & 1) != 0) { //for some reason y is backwards... let's invert it
                edges ^= EDGE_DIR_MASK << 2;
            }
            for (int edge = 0; edge < EDGE_COUNT; edge++) {
                if ((edges & (EDGE_DIR_MASK << (edge << 1))) == EDGE_DIR_NONE) {
                    continue;
                }

                int base = edge * CONNECTION_INDEX_COUNT;
                int provoking = map[vertexMapIndex(dx, dy, dz, base, edge)];
                if (provoking < 0) {
                    continue;
                }
                int c0 = map[vertexMapIndex(dx, dy, dz, base + 1, edge)];
                int c1 = map[vertexMapIndex(dx, dy, dz, base + 2, edge)];
                int oppositeCorner = map[vertexMapIndex(dx, dy, dz, base + 3, edge)];

                if (c0 < 0 || c1 < 0 || oppositeCorner < 0) {
                    if (level == 0) {
                        continue; //skip if any of the vertices are missing
                    }
                    int faceState = data.states[edge];
                    if (this.levelRenderer.isFoliage(faceState)) {
                        continue;
                    }
                    int faceRenderType = this.levelRenderer.renderTypeForState(faceState);
                    if (c0 < 0) {
                        c0 = this.findOrAppendCoarseSeamVertex(srcs, probe, data, dx, dy, dz, base + 1, edge, faceState, faceRenderType, baseX, baseY, baseZ, level, verts);
                        if (c0 < 0) {
                            continue;
                        }
                    }
                    if (c1 < 0) {
                        c1 = this.findOrAppendCoarseSeamVertex(srcs, probe, data, dx, dy, dz, base + 2, edge, faceState, faceRenderType, baseX, baseY, baseZ, level, verts);
                        if (c1 < 0) {
                            continue;
                        }
                    }
                    if (oppositeCorner < 0) {
                        oppositeCorner = this.findOrAppendCoarseSeamVertex(srcs, probe, data, dx, dy, dz, base + 3, edge, faceState, faceRenderType, baseX, baseY, baseZ, level, verts);
                        if (oppositeCorner < 0) {
                            continue;
                        }
                    }
                }

                IndexWriter buf = indices[this.levelRenderer.renderTypeForState(data.states[edge])];

                boolean water = false; //TODO: isWater(data.states[edge]);
                if (water) {
                    edges |= EDGE_DIR_BOTH << (edge << 1);
                }

                if ((edges & (EDGE_DIR_NEGATIVE << (edge << 1))) != 0) { //the face has the negative bit set
                    if ((edges & (EDGE_DIR_POSITIVE << (edge << 1))) != 0) { //the positive bit is set as well, output the face once before flipping
                        this.appendQuad(buf, oppositeCorner, c0, c1, provoking);
                    }

                    //flip the face around
                    int i = c0;
                    c0 = c1;
                    c1 = i;
                }

                this.appendQuad(buf, oppositeCorner, c0, c1, provoking);
            }
        }
    }

    private static int primaryActiveState(TileData probe, int preferredEdge) {
        if (((probe.edges >> (preferredEdge << 1)) & EDGE_DIR_MASK) != EDGE_DIR_NONE && probe.states[preferredEdge] != 0) {
            return probe.states[preferredEdge];
        }
        int[] order = { 1, 0, 2 };
        for (int candidateEdge : order) {
            if (((probe.edges >> (candidateEdge << 1)) & EDGE_DIR_MASK) != EDGE_DIR_NONE && probe.states[candidateEdge] != 0) {
                return probe.states[candidateEdge];
            }
        }
        return 0;
    }

    /**
     * Bridges a missing border corner vertex on coarse LODs ({@code level >= 1}) when an adjacent tile
     * (e.g. clustering scaler vs rough generator) placed the surface within {@link #COARSE_SEAM_SEARCH_RADIUS}
     * voxels above or below.
     */
    protected int findOrAppendCoarseSeamVertex(
            Tile[] srcs, TileData probe, TileData data,
            int dx, int dy, int dz, int i, int edge,
            int faceState, int faceRenderType,
            int baseX, int baseY, int baseZ, int level,
            AttributeWriter<VoxelLocalAttributes> verts) {
        int j = CONNECTION_INDICES[i];
        int gx = dx + ((j >> 2) & 1);
        int gy = dy + ((j >> 1) & 1);
        int gz = dz + (j & 1);

        if (gx != T_VOXELS && gz != T_VOXELS) {
            return -1;
        }

        int bx = gx >> T_SHIFT;
        int bz = gz >> T_SHIFT;
        int lx = gx & T_MASK;
        int lz = gz & T_MASK;

        Tile tileSame = srcs[(bx << 2) | bz];
        Tile tileUp = srcs[(bx << 2) | 2 | bz];
        Tile tileDown = srcs.length >= 12 ? srcs[8 | (bx << 1) | bz] : null;
        if ((gy < T_VOXELS && tileSame == null) || (tileSame == null && tileUp == null && tileDown == null)) {
            return -1;
        }

        boolean wantTranslucent = faceRenderType == RenderConstants.LAYER_TRANSPARENT;
        boolean found = false;
        int matchedNy = 0;
        int matchedX = 0;
        int matchedY = 0;
        int matchedZ = 0;

        boolean haveFallback = false;
        int fallbackNy = 0;
        int fallbackX = 0;
        int fallbackY = 0;
        int fallbackZ = 0;

        SEARCH:
        for (int dist = 1; dist <= COARSE_SEAM_SEARCH_RADIUS; dist++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int ny = gy + dist * sign;
                Tile tile = ny < 0 ? tileDown : (ny < T_VOXELS ? tileSame : tileUp);
                int ly = ny < 0 ? ny + T_VOXELS : (ny < T_VOXELS ? ny : ny - T_VOXELS);
                if (tile == null || ly < 0 || ly >= T_VOXELS || !tile.get(lx, ly, lz, probe)) {
                    continue;
                }

                if (probe.edges != 0) {
                    int probeState = primaryActiveState(probe, edge);
                    if (probeState == 0) {
                        continue;
                    }
                    if ((this.levelRenderer.renderTypeForState(probeState) == RenderConstants.LAYER_TRANSPARENT) != wantTranslucent) {
                        continue;
                    }
                    if (this.levelRenderer.isFoliage(probeState)) {
                        if (!haveFallback) {
                            haveFallback = true;
                            fallbackNy = ny;
                            fallbackX = probe.x;
                            fallbackY = probe.y;
                            fallbackZ = probe.z;
                        }
                        continue;
                    }
                } else if (wantTranslucent) {
                    continue;
                }

                found = true;
                matchedNy = ny;
                matchedX = probe.x;
                matchedY = probe.y;
                matchedZ = probe.z;
                break SEARCH;
            }
        }

        if (!found) {
            if (!haveFallback) {
                return -1;
            }
            matchedNy = fallbackNy;
            matchedX = fallbackX;
            matchedY = fallbackY;
            matchedZ = fallbackZ;
        }

        final int fractMask = this.forceBlockyMesh ? 0 : -1;
        int px = (gx << POS_FRACT_SHIFT) + (matchedX & fractMask);
        int py = Math.max(0, (matchedNy << POS_FRACT_SHIFT) + (matchedY & fractMask));
        int pz = (gz << POS_FRACT_SHIFT) + (matchedZ & fractMask);

        int blockLight = BlockLevelConstants.unpackBlockLight(data.light);
        int skyLight = BlockLevelConstants.unpackSkyLight(data.light);

        verts.append()
                .light((blockLight << 4) | 8, (skyLight << 4) | 8)
                .pos(px, py, pz)
                .state(this.textureUVs.state2index(faceState))
                .color(this.levelRenderer.tintFactorForStateInBiomeAtPos(faceState, data.biome, baseX + (gx << level), baseY + (matchedNy << level), baseZ + (gz << level)))
                .close();
        return verts.size() - 1;
    }

    private void appendQuad(IndexWriter indices, int oppositeCorner, int c0, int c1, int provoking) {
        if (this.useTriangles) {
            indices.appendQuadAsTriangles(oppositeCorner, c0, c1, provoking);
        } else {
            indices.appendQuad(oppositeCorner, c0, c1, provoking);
        }
    }
}
