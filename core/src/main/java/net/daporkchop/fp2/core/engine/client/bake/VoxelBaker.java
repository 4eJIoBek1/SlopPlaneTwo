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
    protected static final int SEAM_SEARCH_RADIUS = 16;

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

        //corners of the +X and +Z border planes which had to be invented because the neighbouring tile has no voxel at exactly the same height. each of them
        //  may leave a vertical slit between the two surfaces, which is sealed by a wall once the mesh has been built.
        final GapList gaps = new GapList();

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
                int provoking = this.vertex(map, data, dx, dy, dz, base, edge, gaps, blockX, blockY, blockZ, level, verts);
                int c0 = this.vertex(map, data, dx, dy, dz, base + 1, edge, gaps, blockX, blockY, blockZ, level, verts);
                int c1 = this.vertex(map, data, dx, dy, dz, base + 2, edge, gaps, blockX, blockY, blockZ, level, verts);
                int oppositeCorner = this.vertex(map, data, dx, dy, dz, base + 3, edge, gaps, blockX, blockY, blockZ, level, verts);

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

        //each border plane is closed from the tile on its negative side only, so that a seam between two tiles is drawn exactly once
        this.writeSkirt(srcs, src, gaps, blockX, blockY, blockZ, level, verts, indices);

        SkirtDiag.tile(new TilePos(level, blockX >> (level + T_SHIFT), blockY >> (level + T_SHIFT), blockZ >> (level + T_SHIFT)), gaps.size, gaps.walls, gaps.aboveWalls, gaps.fallbacks); //TEMP DIAG
    }

    /**
     * The border corners of a tile which had to be invented because the neighbouring tile has no voxel at exactly the same height.
     * <p>
     * Every such corner is where a face of this tile ends at a different height than the matching face of the neighbour, leaving a vertical slit in between
     *  which shows the sky (or a cave) through the terrain. They are collected while the indices are written and sealed by {@link #writeSkirt} afterwards.
     */
    protected static final class GapList {
        static final int STRIDE = 7; //dx, dy, dz, gx, gy, gz, edge

        int[] data = new int[STRIDE << 4];
        int size;

        int aboveWalls; //TEMP DIAG
        int fallbacks; //TEMP DIAG
        int walls; //TEMP DIAG

        void add(int dx, int dy, int dz, int gx, int gy, int gz, int edge) {
            for (int i = 0; i < this.size; i++) { //the same corner is requested once per face of the voxel, and different edges share it
                int base = i * STRIDE;
                if (this.data[base] == dx && this.data[base + 1] == dy && this.data[base + 2] == dz
                        && this.data[base + 3] == gx && this.data[base + 5] == gz) {
                    return;
                }
            }
            if ((this.size + 1) * STRIDE > this.data.length) {
                this.data = Arrays.copyOf(this.data, this.data.length << 1);
            }
            int base = this.size++ * STRIDE;
            this.data[base] = dx;
            this.data[base + 1] = dy;
            this.data[base + 2] = dz;
            this.data[base + 3] = gx;
            this.data[base + 4] = gy;
            this.data[base + 5] = gz;
            this.data[base + 6] = edge;
        }
    }

    /**
     * Seals the vertical slits which the missing border corners (see {@link GapList}) leave between this tile's surface and the neighbouring one's.
     * <p>
     * Unlike a wall spanning whole columns of the border plane, every wall is anchored at the exact lattice point whose corner was missing and reaches the
     *  surface of the neighbouring tile at that same point. This is what makes stair-step geometry (where the neighbouring surface is several blocks away from
     *  the top of the column) seal properly instead of being bridged at the wrong height.
     * <p>
     * The wall belongs to the tile on the negative side of the border (the {@code +X}/{@code +Z} sides only), so that every seam is drawn exactly once instead
     *  of twice with two co-planar walls fighting for the same depth.
     */
    protected static final int TYPE_NONE = 0;
    protected static final int TYPE_BLOCKY = 1;
    protected static final int TYPE_SMOOTH = 2;

    protected static final int AIR_ABOVE = -1;
    protected static final int OVERHANG_SKIP = -2;
    protected static final int SOLID_BELOW = -3;

    protected int tileType(Tile tile, TileData probe) {
        if (tile == null || tile.count() == 0) {
            return TYPE_NONE;
        }
        if (this.forceBlockyMesh) {
            return TYPE_BLOCKY;
        }
        int count = tile.count();
        for (int i = 0; i < count; i++) {
            tile.getOnlyPos(i, probe);
            if (probe.x != 0 || probe.y != 0 || probe.z != 0) {
                return TYPE_SMOOTH;
            }
        }
        return TYPE_BLOCKY;
    }

    protected int groupType(Tile[] tiles, TileData probe) {
        for (Tile t : tiles) {
            if (t != null && t.count() > 0) {
                int type = this.tileType(t, probe);
                if (type != TYPE_NONE) {
                    return type;
                }
            }
        }
        return TYPE_NONE;
    }

    protected boolean isBlocky(Tile tile, TileData probe) {
        return this.tileType(tile, probe) == TYPE_BLOCKY;
    }

    protected void writeSkirt(Tile[] srcs, Tile src, GapList gaps, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts, IndexWriter[] indices) {
        final TileData probe = new TileData();

        // X-axis: our side is dx=0 (srcs[0,2,1,3]), neighbour side is dx=1 (srcs[4,6,5,7])
        int ourTypeX = this.groupType(new Tile[]{ srcs[0], srcs[2], srcs[1], srcs[3] }, probe);
        int theirTypeX = this.groupType(new Tile[]{ srcs[4], srcs[6], srcs[5], srcs[7] }, probe);
        if (ourTypeX != TYPE_NONE || theirTypeX != TYPE_NONE) {
            int ourT = ourTypeX != TYPE_NONE ? ourTypeX : (theirTypeX == TYPE_BLOCKY ? TYPE_SMOOTH : TYPE_BLOCKY);
            int theirT = theirTypeX != TYPE_NONE ? theirTypeX : (ourTypeX == TYPE_BLOCKY ? TYPE_SMOOTH : TYPE_BLOCKY);
            if (ourT != theirT) {
                this.buildSeamAxis(true, ourT, theirT, srcs, gaps, blockX, blockY, blockZ, level, verts, indices);
            }
        }

        // Z-axis: our side is dz=0 (srcs[0,2,4,6]), neighbour side is dz=1 (srcs[1,3,5,7])
        int ourTypeZ = this.groupType(new Tile[]{ srcs[0], srcs[2], srcs[4], srcs[6] }, probe);
        int theirTypeZ = this.groupType(new Tile[]{ srcs[1], srcs[3], srcs[5], srcs[7] }, probe);
        if (ourTypeZ != TYPE_NONE || theirTypeZ != TYPE_NONE) {
            int ourT = ourTypeZ != TYPE_NONE ? ourTypeZ : (theirTypeZ == TYPE_BLOCKY ? TYPE_SMOOTH : TYPE_BLOCKY);
            int theirT = theirTypeZ != TYPE_NONE ? theirTypeZ : (ourTypeZ == TYPE_BLOCKY ? TYPE_SMOOTH : TYPE_BLOCKY);
            if (ourT != theirT) {
                this.buildSeamAxis(false, ourT, theirT, srcs, gaps, blockX, blockY, blockZ, level, verts, indices);
            }
        }
    }

    protected void buildSeamAxis(boolean xAxis, int ourType, int theirType, Tile[] srcs, GapList gaps, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts, IndexWriter[] indices) {
        Tile our0 = srcs[0];
        Tile our1 = srcs[2];
        Tile their0 = xAxis ? srcs[4] : srcs[1];
        Tile their1 = xAxis ? srcs[6] : srcs[3];

        boolean ourIsBlocky = (ourType == TYPE_BLOCKY);
        final int fractMask = this.forceBlockyMesh ? 0 : -1;
        final int tilePlaneTop = T_VOXELS << POS_FRACT_SHIFT;

        final TileData ours = new TileData();
        final TileData theirs = new TileData();

        final int[] rawOur = new int[T_VOXELS];
        final int[] rawTheir = new int[T_VOXELS];
        final int[] clampedOur = new int[T_VOXELS];
        final int[] clampedTheir = new int[T_VOXELS];
        final int[] ourStates = new int[T_VOXELS];
        final int[] theirStates = new int[T_VOXELS];
        final byte[] ourLights = new byte[T_VOXELS];
        final byte[] theirLights = new byte[T_VOXELS];

        final boolean[] validCol = new boolean[T_VOXELS];
        final int[] bY = new int[T_VOXELS];
        final int[] smoothCol = new int[T_VOXELS];
        final int[] smoothCornerY = new int[T_VOXELS + 1];

        final int[] seamState = new int[T_VOXELS];
        final byte[] seamLight = new byte[T_VOXELS];
        final int[] colLow = new int[T_VOXELS];
        final int[] colHigh = new int[T_VOXELS];

        boolean anySeam = false;

        for (int perp = 0; perp < T_VOXELS; perp++) {
            int ourColX = xAxis ? (T_VOXELS - 1) : perp;
            int ourColZ = xAxis ? perp : (T_VOXELS - 1);
            int theirColX = xAxis ? 0 : perp;
            int theirColZ = xAxis ? perp : 0;

            ours.reset();
            int ourY = this.findColumnGroundSurface(our0, our1, ourColX, ourColZ, ours);
            if (ourY >= 0) {
                int fract = ourIsBlocky ? 0 : (ours.y & fractMask);
                int rawVal = (ourY << POS_FRACT_SHIFT) + fract;
                rawOur[perp] = rawVal;
                clampedOur[perp] = Math.max(0, Math.min(tilePlaneTop, rawVal));
                ourStates[perp] = surfaceState(ours, 1);
                ourLights[perp] = ours.light;
            } else if (ourY == SOLID_BELOW) {
                rawOur[perp] = tilePlaneTop;
                clampedOur[perp] = tilePlaneTop;
                ourStates[perp] = 1;
                ourLights[perp] = (byte) ((15 << 4) | 15);
            } else if (ourY == AIR_ABOVE) {
                rawOur[perp] = 0;
                clampedOur[perp] = 0;
                ourStates[perp] = 0;
                ourLights[perp] = 0;
            } else { // OVERHANG_SKIP (-2)
                rawOur[perp] = -1;
                clampedOur[perp] = -1;
                ourStates[perp] = 0;
                ourLights[perp] = 0;
            }

            theirs.reset();
            int theirY = this.findColumnGroundSurface(their0, their1, theirColX, theirColZ, theirs);
            if (theirY >= 0) {
                int fract = (!ourIsBlocky) ? 0 : (theirs.y & fractMask);
                int rawVal = (theirY << POS_FRACT_SHIFT) + fract;
                rawTheir[perp] = rawVal;
                clampedTheir[perp] = Math.max(0, Math.min(tilePlaneTop, rawVal));
                theirStates[perp] = surfaceState(theirs, 1);
                theirLights[perp] = theirs.light;
            } else if (theirY == SOLID_BELOW) {
                rawTheir[perp] = tilePlaneTop;
                clampedTheir[perp] = tilePlaneTop;
                theirStates[perp] = 1;
                theirLights[perp] = (byte) ((15 << 4) | 15);
            } else if (theirY == AIR_ABOVE) {
                rawTheir[perp] = 0;
                clampedTheir[perp] = 0;
                theirStates[perp] = 0;
                theirLights[perp] = 0;
            } else { // OVERHANG_SKIP (-2)
                rawTheir[perp] = -1;
                clampedTheir[perp] = -1;
                theirStates[perp] = 0;
                theirLights[perp] = 0;
            }

            // Determine if column is valid
            if (clampedOur[perp] >= 0 && clampedTheir[perp] >= 0) {
                if (clampedOur[perp] == 0 && clampedTheir[perp] == 0) {
                    // Both in open air: no seam
                    validCol[perp] = false;
                } else if (clampedOur[perp] == tilePlaneTop && clampedTheir[perp] == tilePlaneTop) {
                    // Both underground solid: no seam
                    validCol[perp] = false;
                } else {
                    validCol[perp] = true;
                    if (clampedOur[perp] != clampedTheir[perp]) {
                        anySeam = true;
                    }
                }
            } else {
                // OVERHANG_SKIP on at least one side: do not build curtain
                validCol[perp] = false;
            }

            if (validCol[perp]) {
                int state;
                byte light;
                if (ourY >= 0 && theirY >= 0) {
                    boolean theirsHigher = clampedTheir[perp] >= clampedOur[perp];
                    int sHigh = theirsHigher ? theirStates[perp] : ourStates[perp];
                    int sLow = theirsHigher ? ourStates[perp] : theirStates[perp];
                    byte lHigh = theirsHigher ? theirLights[perp] : ourLights[perp];
                    byte lLow = theirsHigher ? ourLights[perp] : theirLights[perp];

                    state = sHigh;
                    if (sLow != 0 && this.levelRenderer.renderTypeForState(state) != RenderConstants.LAYER_SOLID
                            && this.levelRenderer.renderTypeForState(sLow) == RenderConstants.LAYER_SOLID) {
                        state = sLow;
                    }
                    if (state == 0) {
                        state = sLow != 0 ? sLow : (sHigh != 0 ? sHigh : 1);
                    }
                    light = lHigh != 0 ? lHigh : lLow;
                } else if (ourY >= 0) {
                    state = ourStates[perp];
                    light = ourLights[perp];
                } else if (theirY >= 0) {
                    state = theirStates[perp];
                    light = theirLights[perp];
                } else {
                    state = 1;
                    light = (byte) ((15 << 4) | 15);
                }

                if (state == 0) {
                    state = 1;
                }
                if (light == 0) {
                    light = (byte) ((15 << 4) | 15);
                }
                seamState[perp] = state;
                seamLight[perp] = light;

                if (ourIsBlocky) {
                    bY[perp] = clampedOur[perp];
                    smoothCol[perp] = clampedTheir[perp];
                } else {
                    bY[perp] = clampedTheir[perp];
                    smoothCol[perp] = clampedOur[perp];
                }
            }
        }

        if (!anySeam) {
            return;
        }

        // Interpolate smooth side corner heights (17 corners for 16 columns)
        for (int k = 0; k <= T_VOXELS; k++) {
            boolean hasPrev = (k > 0) && validCol[k - 1];
            boolean hasNext = (k < T_VOXELS) && validCol[k];

            if (hasPrev && hasNext) {
                smoothCornerY[k] = (smoothCol[k - 1] + smoothCol[k]) >> 1;
            } else if (hasPrev) {
                smoothCornerY[k] = smoothCol[k - 1];
            } else if (hasNext) {
                smoothCornerY[k] = smoothCol[k];
            } else {
                smoothCornerY[k] = 0;
            }
        }

        final int plane = tilePlaneTop;
        int wallsBuilt = 0;

        for (int dz = 0; dz < T_VOXELS; dz++) {
            if (!validCol[dz]) {
                continue;
            }

            int b = bY[dz];
            int s0 = smoothCornerY[dz];
            int s1 = smoothCornerY[dz + 1];

            // Flat column: skip quad (Refinement 1)
            if (b == s0 && b == s1) {
                continue;
            }

            int p0 = dz << POS_FRACT_SHIFT;
            int p1 = (dz + 1) << POS_FRACT_SHIFT;

            int state = seamState[dz];
            if (state == 0) {
                if (dz > 0 && seamState[dz - 1] != 0) {
                    state = seamState[dz - 1];
                } else if (dz + 1 < T_VOXELS && seamState[dz + 1] != 0) {
                    state = seamState[dz + 1];
                }
            }
            if (state == 0) {
                state = 1;
            }
            byte light = seamLight[dz];

            int v_B0, v_B1, v_S1, v_S0;
            if (xAxis) {
                v_B0 = this.appendSkirtVertex(light, state, plane, b, p0, blockX, blockY, blockZ, level, verts);
                v_B1 = this.appendSkirtVertex(light, state, plane, b, p1, blockX, blockY, blockZ, level, verts);
                v_S1 = this.appendSkirtVertex(light, state, plane, s1, p1, blockX, blockY, blockZ, level, verts);
                v_S0 = this.appendSkirtVertex(light, state, plane, s0, p0, blockX, blockY, blockZ, level, verts);
            } else {
                v_B0 = this.appendSkirtVertex(light, state, p0, b, plane, blockX, blockY, blockZ, level, verts);
                v_B1 = this.appendSkirtVertex(light, state, p1, b, plane, blockX, blockY, blockZ, level, verts);
                v_S1 = this.appendSkirtVertex(light, state, p1, s1, plane, blockX, blockY, blockZ, level, verts);
                v_S0 = this.appendSkirtVertex(light, state, p0, s0, plane, blockX, blockY, blockZ, level, verts);
            }

            IndexWriter buf = indices[this.levelRenderer.renderTypeForState(state)];
            // Emit double-sided quad (both orientations) to guarantee 100% visibility from any camera angle
            this.appendQuad(buf, v_S1, v_B1, v_S0, v_B0);
            this.appendQuad(buf, v_S1, v_S0, v_B1, v_B0);

            colLow[dz] = Math.min(b, Math.min(s0, s1));
            colHigh[dz] = Math.max(b, Math.max(s0, s1));
            wallsBuilt++;
            gaps.walls++;
        }

        SkirtDiag.recordSeam(new TilePos(level, blockX >> (level + T_SHIFT), blockY >> (level + T_SHIFT), blockZ >> (level + T_SHIFT)), xAxis, wallsBuilt, colLow, colHigh); //TEMP DIAG
    }

    protected int findColumnGroundSurface(Tile tile0, Tile tile1, int colX, int colZ, TileData out) {
        TileData probe = new TileData();

        // 1. Search FIRST in current tile0 (dy=0, height 0..15)
        if (tile0 != null && tile0.count() > 0) {
            int y0 = this.findSurfaceInColumn(tile0, colX, colZ, -1, -1, probe);
            if (y0 >= 0) {
                copyTileData(probe, out);

                // Overhang detection: does this surface voxel have an open ceiling face underneath?
                int ceilingY = -1;
                if (((out.edges >> 2) & EDGE_DIR_NEGATIVE) != 0) {
                    ceilingY = y0;
                }

                int lowerGroundY = -1;
                TileData groundData = new TileData();

                for (int y = y0 - 1; y >= 0; y--) {
                    if (tile0.get(colX, y, colZ, probe) && probe.edges != 0) {
                        if (((probe.edges >> 2) & EDGE_DIR_NEGATIVE) != 0) {
                            ceilingY = y;
                        } else if (ceilingY >= 0 && ((probe.edges >> 2) & EDGE_DIR_POSITIVE) != 0) {
                            int state = surfaceState(probe, 1);
                            if (!this.levelRenderer.isFoliage(state)) {
                                lowerGroundY = y;
                                copyTileData(probe, groundData);
                                break;
                            }
                        }
                    }
                }

                if (ceilingY >= 0) {
                    if (lowerGroundY >= 0) {
                        // There is solid ground below the visor in this tile: seal to lower ground!
                        copyTileData(groundData, out);
                        return lowerGroundY;
                    } else {
                        // Visor hanging over empty air with no ground below in this tile:
                        // SKIP THIS COLUMN! Do not build a 15-block curtain into the canyon!
                        return OVERHANG_SKIP;
                    }
                }

                return y0;
            }
        }

        // 2. No surface in tile0: check if current tile is underground (rock continues up into tile1)
        if (tile1 != null && tile1.count() > 0) {
            int y1 = this.findSurfaceInColumn(tile1, colX, colZ, -1, -1, probe);
            if (y1 >= 0 || tile1.count() > 0) {
                // Rock extends upward: in current tile, solid rock reaches the ceiling
                return SOLID_BELOW;
            }
        }

        // 3. Neither tile0 nor tile1 has solid terrain in this column: open air above ground
        return AIR_ABOVE;
    }

    private static void copyTileData(TileData src, TileData dst) {
        dst.x = src.x;
        dst.y = src.y;
        dst.z = src.z;
        dst.edges = src.edges;
        System.arraycopy(src.states, 0, dst.states, 0, EDGE_COUNT);
        dst.biome = src.biome;
        dst.light = src.light;
    }

    private static boolean getVoxelInStack(Tile tile0, Tile tile1, int colX, int colZ, int stackY, TileData out) {
        if (stackY >= T_VOXELS) {
            return tile1 != null && tile1.get(colX, stackY - T_VOXELS, colZ, out);
        } else {
            return tile0 != null && tile0.get(colX, stackY, colZ, out);
        }
    }

    /**
     * Looks for a non-foliage surface voxel in the given column of a tile, searching downwards from refY (or top-down if refY < 0).
     */
    protected int findSurfaceInColumn(Tile tile, int colX, int colZ, int refY, int type, TileData out) {
        if (tile == null) {
            return -1;
        }

        if (refY < 0) {
            for (int y = T_VOXELS - 1; y >= 0; y--) {
                if (!tile.get(colX, y, colZ, out) || out.edges == 0) {
                    continue;
                }
                int state = surfaceState(out, 1);
                if (this.levelRenderer.isFoliage(state)) {
                    continue;
                }
                if (type < 0 || this.levelRenderer.renderTypeForState(state) == type) {
                    return y;
                }
            }
            return -1;
        }

        for (int d = 0; d <= SEAM_SEARCH_RADIUS; d++) {
            for (int side = 0, sides = (d > 0 ? 2 : 1); side < sides; side++) {
                final int y = side == 0 ? refY - d : refY + d;
                if (y < 0 || y >= T_VOXELS) {
                    continue;
                }

                if (!tile.get(colX, y, colZ, out) || out.edges == 0) {
                    continue;
                }
                int state = surfaceState(out, 1);
                if (this.levelRenderer.isFoliage(state)) {
                    continue;
                }
                if (type < 0 || this.levelRenderer.renderTypeForState(state) == type) {
                    return y;
                }
            }
        }
        return -1;
    }

    protected int appendSkirtVertex(byte light, int state, int x, int y, int z, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts) {
        //TEMP DIAG: force color to magenta (255, 0, 255) for visual verification in client
        int color = 0xFFFF00FF;

        int blockLight = BlockLevelConstants.unpackBlockLight(light);
        int skyLight = BlockLevelConstants.unpackSkyLight(light);
        if (skyLight < 12) {
            skyLight = 15;
        }

        verts.append()
                .light((blockLight << 4) | 8, (skyLight << 4) | 8)
                .pos(x, y, z)
                .state(this.textureUVs.state2index(state))
                .color(color)
                .close();
        return verts.size() - 1;
    }

    /**
     * Gets the index of the vertex at the given corner of a face, or invents one if the tile which should have provided it didn't.
     * <p>
     * The corners on the border planes of a tile are supplied by the neighbouring tiles, which is why only the first layer of the neighbours is written into
     *  the vertex map. If a neighbour was produced by another generator or from a coarser detail level, its surface sits at a different height and it has no
     *  voxel where the corner expects one. The missing vertex is then placed at our own surface (keeping the face on the border plane) and the column is
     *  recorded, so that {@link #writeSkirt} can seal the vertical gap between the two surfaces afterwards.
     */
    protected int vertex(int[] map, TileData data, int dx, int dy, int dz, int i, int edge, GapList gaps, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts) {
        int j = CONNECTION_INDICES[i];
        int gx = dx + ((j >> 2) & 1);
        int gy = dy + ((j >> 1) & 1);
        int gz = dz + (j & 1);

        int baseMapIndex = ((gx * T_VERTS + gy) * T_VERTS + gz) * EDGE_COUNT;
        int index = baseMapIndex + edge;
        int vertex = map[index];
        if (vertex < 0) { //the tile which would have to provide this vertex didn't have it
            for (int e = 0; e < EDGE_COUNT; e++) {
                if (map[baseMapIndex + e] >= 0) {
                    map[index] = vertex = map[baseMapIndex + e];
                    return vertex;
                }
            }
            SkirtDiag.missing(gx == T_VOXELS, gz == T_VOXELS); //TEMP DIAG
            if (gx == T_VOXELS || gz == T_VOXELS) { //a face of ours ends at a different height than the neighbour's, so a wall will be needed
                gaps.add(dx, dy, dz, gx, gy, gz, edge);
            }
            map[index] = vertex = this.appendFilledVertex(data, gx, gy, gz, edge, blockX, blockY, blockZ, level, verts);
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
     * Appends a vertex for a corner of the given face which should have been provided by a neighbouring tile, but wasn't.
     * <p>
     * The position is the given grid position offset by the fractional offsets of the voxel the face belongs to, which keeps the face on the border plane. The
     *  attributes are taken from the same voxel, so that the patch keeps the texture and the light of our own terrain.
     */
    protected int appendFilledVertex(TileData data, int gx, int gy, int gz, int edge, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts) {
        final int fractMask = this.forceBlockyMesh ? 0 : -1;

        int posX = gx == T_VOXELS ? (T_VOXELS << POS_FRACT_SHIFT) : (gx == 0 ? 0 : (gx << POS_FRACT_SHIFT) + (data.x & fractMask));
        int posY = (gy << POS_FRACT_SHIFT) + (data.y & fractMask);
        int posZ = gz == T_VOXELS ? (T_VOXELS << POS_FRACT_SHIFT) : (gz == 0 ? 0 : (gz << POS_FRACT_SHIFT) + (data.z & fractMask));

        int state = surfaceState(data, edge);

        verts.append()
                .light((BlockLevelConstants.unpackBlockLight(data.light) << 4) | 8, (BlockLevelConstants.unpackSkyLight(data.light) << 4) | 8)
                .pos(posX, posY, posZ)
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
