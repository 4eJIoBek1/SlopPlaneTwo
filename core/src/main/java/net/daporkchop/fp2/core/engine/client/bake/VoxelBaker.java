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
                new TilePos(level, x, y, z));
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

            //step 2: clamp boundary vertices so surface quads at border connect properly
            this.clampBoundaryVertices(srcs[0], pos.minBlockX(), pos.minBlockY(), pos.minBlockZ(), pos.level(), map, output.verts);

            //step 3: write indices to actually connect the vertices and build the mesh
            this.writeIndices(srcs[0], map, output.indicesPerPass);

            //step 4: write vertical skirts for boundary edges exposed to air/void
            this.writeSkirts(srcs, map, output.indicesPerPass, output.verts);
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

    protected void writeIndices(Tile src, int[] map, IndexWriter[] indices) {
        final TileData data = new TileData();

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
                int oppositeCorner, c0, c1, provoking;
                if ((provoking = map[vertexMapIndex(dx, dy, dz, base, edge)]) < 0
                        || (c0 = map[vertexMapIndex(dx, dy, dz, base + 1, edge)]) < 0
                        || (c1 = map[vertexMapIndex(dx, dy, dz, base + 2, edge)]) < 0
                        || (oppositeCorner = map[vertexMapIndex(dx, dy, dz, base + 3, edge)]) < 0) {
                    continue; //skip if any of the vertices are missing
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

    private void appendQuad(IndexWriter indices, int oppositeCorner, int c0, int c1, int provoking) {
        if (this.useTriangles) {
            indices.appendQuadAsTriangles(oppositeCorner, c0, c1, provoking);
        } else {
            indices.appendQuad(oppositeCorner, c0, c1, provoking);
        }
    }

    protected void clampBoundaryVertices(Tile src, int blockX, int blockY, int blockZ, int level, int[] map, AttributeWriter<VoxelLocalAttributes> verts) {
        final TileData data = new TileData();
        for (int j = 0; j < src.count(); j++) {
            int voxelPos = src.get(j, data);
            int dx = (voxelPos >> (T_SHIFT << 1)) & T_MASK;
            int dy = (voxelPos >> T_SHIFT) & T_MASK;
            int dz = voxelPos & T_MASK;

            int edges = data.edges;
            if ((((edges >> 2) ^ (edges >> 3)) & 1) != 0) {
                edges ^= EDGE_DIR_MASK << 2;
            }
            if ((edges & (EDGE_DIR_MASK << 2)) == EDGE_DIR_NONE) {
                continue;
            }

            int state = data.states[1];
            if (this.levelRenderer.renderTypeForState(state) != 0) {
                continue; // Only solid opaque blocks get boundary clamping, never water or foliage
            }

            if (dx == 15) {
                this.ensureBoundaryVertex(blockX, blockY, blockZ, level, 16, dy, dz, data, verts, map);
                this.ensureBoundaryVertex(blockX, blockY, blockZ, level, 16, dy, dz + 1, data, verts, map);
            }
            if (dz == 15) {
                this.ensureBoundaryVertex(blockX, blockY, blockZ, level, dx, dy, 16, data, verts, map);
                this.ensureBoundaryVertex(blockX, blockY, blockZ, level, dx + 1, dy, 16, data, verts, map);
            }
            if (dx == 15 && dz == 15) {
                this.ensureBoundaryVertex(blockX, blockY, blockZ, level, 16, dy, 16, data, verts, map);
            }
        }
    }

    protected void ensureBoundaryVertex(int blockX, int blockY, int blockZ, int level, int x, int y, int z, TileData data, AttributeWriter<VoxelLocalAttributes> vertices, int[] map) {
        int baseMapIndex = ((x * T_VERTS + y) * T_VERTS + z) * 3;
        if (map[baseMapIndex + 1] >= 0) {
            return;
        }

        final int fractMask = this.forceBlockyMesh ? 0 : -1;
        int blockLight = BlockLevelConstants.unpackBlockLight(data.light);
        int skyLight = BlockLevelConstants.unpackSkyLight(data.light);

        int posX = (x << POS_FRACT_SHIFT) + (x == 16 ? 0 : (data.x & fractMask));
        int posY = (y << POS_FRACT_SHIFT) + (data.y & fractMask);
        int posZ = (z << POS_FRACT_SHIFT) + (z == 16 ? 0 : (data.z & fractMask));

        int state = data.states[1];
        int color = this.levelRenderer.tintFactorForStateInBiomeAtPos(state, data.biome, blockX + (x << level), blockY + (y << level), blockZ + (z << level));

        vertices.append()
                .light((blockLight << 4) | 8, (skyLight << 4) | 8)
                .pos(posX, posY, posZ)
                .state(this.textureUVs.state2index(state))
                .color(color)
                .close();

        int vertIdx = vertices.size() - 1;
        for (int edge = 0; edge < EDGE_COUNT; edge++) {
            if (map[baseMapIndex + edge] < 0) {
                map[baseMapIndex + edge] = vertIdx;
            }
        }
    }

    protected void writeSkirts(Tile[] srcs, int[] map, IndexWriter[] indices, AttributeWriter<VoxelLocalAttributes> verts) {
        Tile src0 = srcs[0];
        if (src0 == null) {
            return;
        }

        final TileData data = new TileData();
        final TileData scratchData = new TileData();
        final int fractMask = this.forceBlockyMesh ? 0 : -1;

        for (int j = 0; j < src0.count(); j++) {
            int voxelPos = src0.get(j, data);
            int dx = (voxelPos >> (T_SHIFT << 1)) & T_MASK;
            int dy = (voxelPos >> T_SHIFT) & T_MASK;
            int dz = voxelPos & T_MASK;

            int edges = data.edges;
            if ((((edges >> 2) ^ (edges >> 3)) & 1) != 0) {
                edges ^= EDGE_DIR_MASK << 2;
            }
            if ((edges & (EDGE_DIR_MASK << 2)) == EDGE_DIR_NONE) {
                continue;
            }

            int state = data.states[1];
            if (this.levelRenderer.renderTypeForState(state) != 0) {
                continue; // Only solid opaque blocks get boundary clamping, never water or foliage
            }

            int topY = (dy << POS_FRACT_SHIFT) + (data.y & fractMask);
            if (topY <= 0) {
                continue;
            }

            IndexWriter buf = indices[this.levelRenderer.renderTypeForState(state)];

            // +X boundary skirt
            if (dx == 15) {
                boolean exposed = (srcs[4] == null || !srcs[4].get(0, dy, dz, scratchData));
                if (exposed) {
                    int v_top_0 = map[vertexMapIndex(15, dy, dz, 6, 1)]; // (16, dy, dz)
                    int v_top_1 = map[vertexMapIndex(15, dy, dz, 7, 1)]; // (16, dy, dz + 1)
                    if (v_top_0 >= 0 && v_top_1 >= 0) {
                        int v_bot_0 = this.appendBottomVertex(verts, 16 << POS_FRACT_SHIFT, 0, (dz << POS_FRACT_SHIFT) + (data.z & fractMask), v_top_0);
                        int v_bot_1 = this.appendBottomVertex(verts, 16 << POS_FRACT_SHIFT, 0, ((dz + 1) << POS_FRACT_SHIFT) + (data.z & fractMask), v_top_1);
                        this.appendQuad(buf, v_bot_1, v_bot_0, v_top_1, v_top_0);
                    }
                }
            }

            // -X boundary skirt
            if (dx == 0) {
                int v_top_0 = map[vertexMapIndex(0, dy, dz, 4, 1)]; // (0, dy, dz)
                int v_top_1 = map[vertexMapIndex(0, dy, dz, 5, 1)]; // (0, dy, dz + 1)
                if (v_top_0 >= 0 && v_top_1 >= 0) {
                    int v_bot_0 = this.appendBottomVertex(verts, (dx << POS_FRACT_SHIFT) + (data.x & fractMask), 0, (dz << POS_FRACT_SHIFT) + (data.z & fractMask), v_top_0);
                    int v_bot_1 = this.appendBottomVertex(verts, (dx << POS_FRACT_SHIFT) + (data.x & fractMask), 0, ((dz + 1) << POS_FRACT_SHIFT) + (data.z & fractMask), v_top_1);
                    this.appendQuad(buf, v_bot_1, v_top_1, v_bot_0, v_top_0);
                }
            }

            // +Z boundary skirt
            if (dz == 15) {
                boolean exposed = (srcs[1] == null || !srcs[1].get(dx, dy, 0, scratchData));
                if (exposed) {
                    int v_top_0 = map[vertexMapIndex(dx, dy, 15, 5, 1)]; // (dx, dy, 16)
                    int v_top_1 = map[vertexMapIndex(dx, dy, 15, 7, 1)]; // (dx + 1, dy, 16)
                    if (v_top_0 >= 0 && v_top_1 >= 0) {
                        int v_bot_0 = this.appendBottomVertex(verts, (dx << POS_FRACT_SHIFT) + (data.x & fractMask), 0, 16 << POS_FRACT_SHIFT, v_top_0);
                        int v_bot_1 = this.appendBottomVertex(verts, ((dx + 1) << POS_FRACT_SHIFT) + (data.x & fractMask), 0, 16 << POS_FRACT_SHIFT, v_top_1);
                        this.appendQuad(buf, v_bot_1, v_top_1, v_bot_0, v_top_0);
                    }
                }
            }

            // -Z boundary skirt
            if (dz == 0) {
                int v_top_0 = map[vertexMapIndex(dx, dy, 0, 4, 1)]; // (dx, dy, 0)
                int v_top_1 = map[vertexMapIndex(dx, dy, 0, 6, 1)]; // (dx + 1, dy, 0)
                if (v_top_0 >= 0 && v_top_1 >= 0) {
                    int v_bot_0 = this.appendBottomVertex(verts, (dx << POS_FRACT_SHIFT) + (data.x & fractMask), 0, (dz << POS_FRACT_SHIFT) + (data.z & fractMask), v_top_0);
                    int v_bot_1 = this.appendBottomVertex(verts, ((dx + 1) << POS_FRACT_SHIFT) + (data.x & fractMask), 0, (dz << POS_FRACT_SHIFT) + (data.z & fractMask), v_top_1);
                    this.appendQuad(buf, v_bot_1, v_bot_0, v_top_1, v_top_0);
                }
            }
        }
    }

    private int appendBottomVertex(AttributeWriter<VoxelLocalAttributes> verts, int x, int y, int z, int srcVertIdx) {
        verts.appendCopy(srcVertIdx).pos(x, y, z).close();
        return verts.size() - 1;
    }
}
