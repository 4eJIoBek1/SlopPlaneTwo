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
    /**
     * How many voxels the search for the surface of a neighbouring tile may descend or ascend. A bigger step at a tile border (a cliff, where the surfaces
     *  of the two tiles have nothing to do with each other) is left alone, as stretching a face that far would look worse than the gap itself.
     */
    protected static final int SEAM_SEARCH_RADIUS = 8;

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

    protected void writeIndices(Tile[] srcs, Tile src, int blockX, int blockY, int blockZ, int level, int[] map, AttributeWriter<VoxelLocalAttributes> verts, IndexWriter[] indices) {
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
                int provoking = this.vertex(srcs, probe, map, data, dx, dy, dz, base, edge, blockX, blockY, blockZ, level, verts);
                int c0 = this.vertex(srcs, probe, map, data, dx, dy, dz, base + 1, edge, blockX, blockY, blockZ, level, verts);
                int c1 = this.vertex(srcs, probe, map, data, dx, dy, dz, base + 2, edge, blockX, blockY, blockZ, level, verts);
                int oppositeCorner = this.vertex(srcs, probe, map, data, dx, dy, dz, base + 3, edge, blockX, blockY, blockZ, level, verts);

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

    /**
     * Gets the index of the vertex at the given corner of a face.
     * <p>
     * The corners on the border planes of a tile are supplied by the neighbouring tiles, which is why only the first layer of the neighbours is written into
     *  the vertex map. If the neighbour was produced by another generator (a tile overlapping vanilla terrain is scaled from it, while the one beyond it is
     *  generated from a density field) or from a coarser detail level, its surface sits at a different height and it has no voxel where this corner expects
     *  one, in which case the face would previously have been dropped completely and left a hole in the terrain.
     * <p>
     * Instead, the missing vertex is pinned onto the surface of the neighbouring tile: the closest voxel of it in the same column is used, so that the face
     *  of this tile reaches the neighbouring surface. Merely clamping the corner onto the border plane (the fallback for when the neighbour has no voxel
     *  nearby) is not enough, as both surfaces would then just end at the border at different heights with a see-through slit in between.
     */
    protected int vertex(Tile[] srcs, TileData probe, int[] map, TileData data, int dx, int dy, int dz, int i, int edge, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts) {
        int j = CONNECTION_INDICES[i];
        int gx = dx + ((j >> 2) & 1);
        int gy = dy + ((j >> 1) & 1);
        int gz = dz + (j & 1);

        int index = ((gx * T_VERTS + gy) * T_VERTS + gz) * EDGE_COUNT + edge;
        int vertex = map[index];
        if (vertex < 0) { //the tile which would have to provide this vertex didn't have it
            int neighbourGy = this.findNeighbourVoxel(srcs, gx, gy, gz, edge, this.levelRenderer.renderTypeForState(surfaceState(data, edge)), level, probe);
            map[index] = vertex = this.appendFilledVertex(data, gx, neighbourGy < 0 ? gy : neighbourGy, gz, edge, blockX, blockY, blockZ, level, verts, neighbourGy < 0 ? data : probe);
        }
        return vertex;
    }

    /**
     * Gets the block state which is responsible for the geometry of the given edge of a voxel, falling back to the other axes if the edge itself carries none.
     */
    protected static int surfaceState(TileData data, int edge) {
        int state = data.states[edge];
        if (state == 0) { //fall back to the state of another axis if this one carries none
            state = data.states[1];
        }
        if (state == 0) {
            state = data.states[0] != 0 ? data.states[0] : data.states[2];
        }
        return state;
    }

    /**
     * Finds the voxel of the neighbouring tile which is closest to the requested height in the column of the given corner. Since a corner on a border plane
     *  belongs to a neighbouring tile, the column is looked up there; the vertical search covers the height difference between two generators or detail
     *  levels meeting at the border.
     * <p>
     * Only voxels belonging to the same kind of surface are accepted: pinning a corner of solid terrain onto the surface of water (or the other way around)
     *  drags the face down to the water level or up to the shore, which leaves a thin sliver of sky along the shoreline. The water and the shore are separate
     *  surfaces, so they are left to end at the border plane as before.
     *
     * @param out receives the found voxel
     * @return the grid Y of the found voxel, or {@code -1} if the corner isn't on a border plane, the neighbouring tile is missing, or it has no voxel of a
     *         matching surface anywhere near the requested height
     */
    protected int findNeighbourVoxel(Tile[] srcs, int gx, int gy, int gz, int edge, int faceType, int level, TileData out) {
        int tileIndex = 0;
        if (gx == T_VOXELS) {
            tileIndex |= 4;
        }
        if (gz == T_VOXELS) {
            tileIndex |= 1;
        }
        if (tileIndex == 0) { //the corner is inside our own tile, so the neighbours have nothing to do with it
            return -1;
        }

        Tile neighbour = srcs[tileIndex];
        if (neighbour == null) { //the neighbouring tile isn't loaded yet
            return -1;
        }

        final int lx = gx & T_MASK;
        final int lz = gz & T_MASK;
        final boolean transparent = faceType == RenderConstants.LAYER_TRANSPARENT;

        for (int d = 0; d <= SEAM_SEARCH_RADIUS; d++) {
            for (int side = 0; side < (d == 0 ? 1 : 2); side++) {
                int y = side == 0 ? gy + d : gy - d;
                if (y < 0 || y >= T_VOXELS || !neighbour.get(lx, y, lz, out) || out.edges == 0) {
                    continue;
                }
                if ((this.levelRenderer.renderTypeForState(surfaceState(out, edge)) == RenderConstants.LAYER_TRANSPARENT) == transparent) {
                    return y;
                }
            }
        }

        return -1;
    }

    /**
     * Appends a vertex for a corner of the given face which should have been provided by a neighbouring tile, but wasn't.
     * <p>
     * The position is the given grid position offset by the fractional offsets of {@code src}: either the voxel the face belongs to (clamped onto the corner
     *  of its cell, which keeps the face on the border plane) or the voxel of the neighbouring tile the corner was pinned onto. The remaining attributes are
     *  always taken from the voxel the face belongs to, so that the patch keeps the texture and the light of our own terrain.
     */
    protected int appendFilledVertex(TileData data, int gx, int gy, int gz, int edge, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts, TileData src) {
        final int fractMask = this.forceBlockyMesh ? 0 : -1;

        int state = surfaceState(data, edge);

        verts.append()
                .light((BlockLevelConstants.unpackBlockLight(data.light) << 4) | 8, (BlockLevelConstants.unpackSkyLight(data.light) << 4) | 8)
                .pos(cornerPos(gx, src.x & fractMask), cornerPos(gy, src.y & fractMask), cornerPos(gz, src.z & fractMask))
                .state(this.textureUVs.state2index(state))
                .color(this.levelRenderer.tintFactorForStateInBiomeAtPos(state, data.biome, blockX + (gx << level), blockY + (gy << level), blockZ + (gz << level)))
                .close();
        return verts.size() - 1;
    }

    /**
     * Gets the position of the given grid coordinate within the tile.
     * <p>
     * On the far border plane (grid == {@link net.daporkchop.fp2.core.engine.EngineConstants#T_VOXELS}) the offset is deliberately kept as well, which pushes
     *  the vertex past the tile border by up to one voxel: the neighbouring tile's surface sits at a different height (it was produced by a different
     *  generator, or by a coarser detail level), so the two tiles only meet if each of them spills over the border by one voxel. Ending both surfaces exactly
     *  on the border plane instead leaves a one-voxel-tall slit between them for as long as the border is, which is visible as a hole showing the sky.
     */
    private static int cornerPos(int grid, int fract) {
        return (grid << POS_FRACT_SHIFT) + fract;
    }

    private void appendQuad(IndexWriter indices, int oppositeCorner, int c0, int c1, int provoking) {
        if (this.useTriangles) {
            indices.appendQuadAsTriangles(oppositeCorner, c0, c1, provoking);
        } else {
            indices.appendQuad(oppositeCorner, c0, c1, provoking);
        }
    }
}
