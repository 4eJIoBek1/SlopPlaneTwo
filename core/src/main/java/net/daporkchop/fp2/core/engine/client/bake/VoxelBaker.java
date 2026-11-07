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
    /**
     * Checks whether a tile is blocky (has no non-zero fractional vertex offsets).
     * Exact vanilla chunks and flat generators produce blocky tiles with all-zero offsets.
     */
    protected boolean isBlocky(Tile tile, TileData probe) {
        if (tile == null || tile.count() == 0) {
            return false;
        }
        if (this.forceBlockyMesh) {
            return true;
        }
        int count = tile.count();
        for (int i = 0; i < count; i++) {
            tile.getOnlyPos(i, probe);
            if (probe.x != 0 || probe.y != 0 || probe.z != 0) {
                return false;
            }
        }
        return true;
    }

    protected void writeSkirt(Tile[] srcs, Tile src, GapList gaps, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts, IndexWriter[] indices) {
        if (src == null || src.count() == 0) {
            return;
        }

        Tile neighbourX = srcs[4];
        Tile neighbourZ = srcs[1];

        if (neighbourX == null && neighbourZ == null) {
            return;
        }

        final TileData probe = new TileData();
        boolean srcBlocky = isBlocky(src, probe);

        if (neighbourX != null) {
            Tile tileToCheck = neighbourX.count() > 0 ? neighbourX : (srcs[6] != null && srcs[6].count() > 0 ? srcs[6] : null);
            if (tileToCheck != null) {
                boolean neighbourXBlocky = isBlocky(tileToCheck, probe);
                if (srcBlocky != neighbourXBlocky) {  // ONLY at rough↔scaler boundary
                    this.buildSeamAxis(true, srcs, gaps, blockX, blockY, blockZ, level, verts, indices);
                }
            }
        }
        if (neighbourZ != null) {
            Tile tileToCheck = neighbourZ.count() > 0 ? neighbourZ : (srcs[3] != null && srcs[3].count() > 0 ? srcs[3] : null);
            if (tileToCheck != null) {
                boolean neighbourZBlocky = isBlocky(tileToCheck, probe);
                if (srcBlocky != neighbourZBlocky) {  // ONLY at rough↔scaler boundary
                    this.buildSeamAxis(false, srcs, gaps, blockX, blockY, blockZ, level, verts, indices);
                }
            }
        }
    }

    protected void buildSeamAxis(boolean xAxis, Tile[] srcs, GapList gaps, int blockX, int blockY, int blockZ, int level, AttributeWriter<VoxelLocalAttributes> verts, IndexWriter[] indices) {
        Tile src = srcs[0];
        Tile neighbour = xAxis ? srcs[4] : srcs[1];
        Tile aboveNeighbour = xAxis ? srcs[6] : srcs[3];
        Tile aboveSrc = srcs[2];
        if (src == null || neighbour == null) {
            return;
        }

        final int fractMask = this.forceBlockyMesh ? 0 : -1;
        final TileData ours = new TileData();
        final TileData theirs = new TileData();

        final int[] rawOur = new int[T_VOXELS];
        final int[] rawTheir = new int[T_VOXELS];
        final int[] ourStates = new int[T_VOXELS];
        final int[] theirStates = new int[T_VOXELS];
        final byte[] ourLights = new byte[T_VOXELS];
        final byte[] theirLights = new byte[T_VOXELS];

        int knownOurCount = 0;
        int knownTheirCount = 0;

        for (int perp = 0; perp < T_VOXELS; perp++) {
            int ourColX = xAxis ? (T_VOXELS - 1) : perp;
            int ourColZ = xAxis ? perp : (T_VOXELS - 1);
            int theirColX = xAxis ? 0 : perp;
            int theirColZ = xAxis ? perp : 0;

            ours.reset();
            int ourY = this.findSurfaceInColumn(src, ourColX, ourColZ, -1, -1, ours);
            if (ourY >= 0) {
                rawOur[perp] = (ourY << POS_FRACT_SHIFT) + (ours.y & fractMask);
                ourStates[perp] = surfaceState(ours, 1);
                ourLights[perp] = ours.light;
                knownOurCount++;
            } else if (aboveSrc != null) {
                int aboveOurY = this.findSurfaceInColumn(aboveSrc, ourColX, ourColZ, -1, -1, ours);
                if (aboveOurY >= 0) {
                    ourY = aboveOurY + T_VOXELS;
                    rawOur[perp] = (ourY << POS_FRACT_SHIFT) + (ours.y & fractMask);
                    ourStates[perp] = surfaceState(ours, 1);
                    ourLights[perp] = ours.light;
                    knownOurCount++;
                } else {
                    rawOur[perp] = -1;
                }
            } else {
                rawOur[perp] = -1;
            }

            theirs.reset();
            int theirY = -1;
            if (ourY >= 0) {
                int refNeighbour = ourY >= T_VOXELS ? ourY - T_VOXELS : ourY;
                Tile primaryNeighbour = ourY >= T_VOXELS && aboveNeighbour != null ? aboveNeighbour : neighbour;
                theirY = this.findSurfaceInColumn(primaryNeighbour, theirColX, theirColZ, refNeighbour, -1, theirs);
                if (theirY >= 0 && primaryNeighbour == aboveNeighbour) {
                    theirY += T_VOXELS;
                }
                if (theirY < 0 && aboveNeighbour != null && primaryNeighbour == neighbour) {
                    int refAbove = Math.max(0, ourY - T_VOXELS);
                    int aboveY = this.findSurfaceInColumn(aboveNeighbour, theirColX, theirColZ, refAbove, -1, theirs);
                    if (aboveY >= 0) {
                        theirY = aboveY + T_VOXELS;
                    }
                }
            } else {
                theirY = this.findSurfaceInColumn(neighbour, theirColX, theirColZ, -1, -1, theirs);
                if (theirY < 0 && aboveNeighbour != null) {
                    int aboveY = this.findSurfaceInColumn(aboveNeighbour, theirColX, theirColZ, -1, -1, theirs);
                    if (aboveY >= 0) {
                        theirY = aboveY + T_VOXELS;
                    }
                }
            }

            if (theirY >= 0) {
                rawTheir[perp] = (theirY << POS_FRACT_SHIFT) + (theirs.y & fractMask);
                theirStates[perp] = surfaceState(theirs, 1);
                theirLights[perp] = theirs.light;
                knownTheirCount++;
            } else {
                rawTheir[perp] = -1;
            }
        }

        // If neither tile has any surface in any column, nothing to connect
        if (knownOurCount == 0 && knownTheirCount == 0) {
            return;
        }

        // Phase 2: Bridge missing surfaces by propagating along the seam
        if (knownOurCount > 0 && knownOurCount < T_VOXELS) {
            fillMissingFromNearest(rawOur, ourStates, ourLights);
        }
        if (knownTheirCount > 0 && knownTheirCount < T_VOXELS) {
            fillMissingFromNearest(rawTheir, theirStates, theirLights);
        }

        // Phase 3: If an entire tile has no surface, determine if it represents solid rock (higher) or air (lower)
        final int tilePlaneTop = T_VOXELS << POS_FRACT_SHIFT;
        if (knownOurCount == 0) {
            if (aboveSrc != null && aboveSrc.count() > 0) {
                Arrays.fill(rawOur, tilePlaneTop);
                for (int p = 0; p < T_VOXELS; p++) {
                    ourStates[p] = theirStates[p];
                    ourLights[p] = theirLights[p];
                }
            } else {
                Arrays.fill(rawOur, 0);
                for (int p = 0; p < T_VOXELS; p++) {
                    ourStates[p] = theirStates[p];
                    ourLights[p] = theirLights[p];
                }
            }
        }
        if (knownTheirCount == 0) {
            if (aboveNeighbour != null && aboveNeighbour.count() > 0) {
                Arrays.fill(rawTheir, tilePlaneTop);
                for (int p = 0; p < T_VOXELS; p++) {
                    theirStates[p] = ourStates[p];
                    theirLights[p] = ourLights[p];
                }
            } else {
                Arrays.fill(rawTheir, 0);
                for (int p = 0; p < T_VOXELS; p++) {
                    theirStates[p] = ourStates[p];
                    theirLights[p] = ourLights[p];
                }
            }
        }

        final boolean[] hasSeam = new boolean[T_VOXELS];
        final int[] colLow = new int[T_VOXELS];
        final int[] colHigh = new int[T_VOXELS];
        final int[] seamState = new int[T_VOXELS];
        final byte[] seamLight = new byte[T_VOXELS];
        boolean anySeam = false;

        for (int perp = 0; perp < T_VOXELS; perp++) {
            int ourSurface = rawOur[perp];
            int theirSurface = rawTheir[perp];

            int diff = Math.abs(ourSurface - theirSurface);
            if (diff > 0) {
                int low = Math.max(0, Math.min(ourSurface, theirSurface));
                int high = Math.min(tilePlaneTop, Math.max(ourSurface, theirSurface));
                colLow[perp] = low;
                colHigh[perp] = high;
                if (high > low) {
                    hasSeam[perp] = true;
                    boolean theirsHigher = theirSurface >= ourSurface;
                    int sHigh = theirsHigher ? theirStates[perp] : ourStates[perp];
                    int sLow = theirsHigher ? ourStates[perp] : theirStates[perp];
                    int state = sHigh;
                    if (this.levelRenderer.renderTypeForState(state) != RenderConstants.LAYER_SOLID
                            && this.levelRenderer.renderTypeForState(sLow) == RenderConstants.LAYER_SOLID) {
                        state = sLow;
                    }
                    if (state == 0) {
                        state = sLow != 0 ? sLow : (sHigh != 0 ? sHigh : 1);
                    }
                    seamState[perp] = state;
                    byte light = theirsHigher ? theirLights[perp] : ourLights[perp];
                    seamLight[perp] = light != 0 ? light : (byte) ((15 << 4) | 15);
                    anySeam = true;
                }
            } else {
                colLow[perp] = Math.max(0, Math.min(ourSurface, theirSurface));
                colHigh[perp] = colLow[perp];
            }
        }

        if (!anySeam) {
            return;
        }

        // Interpolate corner heights (17 corners for 16 columns) to ensure adjacent quads meet with zero gap
        final int[] cornerLow = new int[T_VOXELS + 1];
        final int[] cornerHigh = new int[T_VOXELS + 1];

        for (int k = 0; k <= T_VOXELS; k++) {
            boolean hasPrev = (k > 0) && hasSeam[k - 1];
            boolean hasNext = (k < T_VOXELS) && hasSeam[k];

            if (hasPrev && hasNext) {
                cornerLow[k] = (colLow[k - 1] + colLow[k]) >> 1;
                cornerHigh[k] = (colHigh[k - 1] + colHigh[k]) >> 1;
            } else if (hasPrev) {
                cornerLow[k] = colLow[k - 1];
                cornerHigh[k] = colHigh[k - 1];
            } else if (hasNext) {
                cornerLow[k] = colLow[k];
                cornerHigh[k] = colHigh[k];
            } else {
                cornerLow[k] = 0;
                cornerHigh[k] = 0;
            }
        }

        final int plane = tilePlaneTop;
        int wallsBuilt = 0;

        for (int dz = 0; dz < T_VOXELS; dz++) {
            if (!hasSeam[dz]) {
                continue;
            }

            int p0 = dz << POS_FRACT_SHIFT;
            int p1 = (dz + 1) << POS_FRACT_SHIFT;
            int low0 = cornerLow[dz];
            int high0 = cornerHigh[dz];
            int low1 = cornerLow[dz + 1];
            int high1 = cornerHigh[dz + 1];

            if (high0 <= low0 && high1 <= low1) {
                continue;
            }

            int top0 = Math.min(plane, high0 + 1);
            int top1 = Math.min(plane, high1 + 1);
            int bot0 = Math.max(0, low0 - 1);
            int bot1 = Math.max(0, low1 - 1);

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

            int v_BL, v_BR, v_TR, v_TL;
            if (xAxis) {
                v_BL = this.appendSkirtVertex(light, state, plane, bot0, p0, blockX, blockY, blockZ, level, verts);
                v_BR = this.appendSkirtVertex(light, state, plane, bot1, p1, blockX, blockY, blockZ, level, verts);
                v_TR = this.appendSkirtVertex(light, state, plane, top1, p1, blockX, blockY, blockZ, level, verts);
                v_TL = this.appendSkirtVertex(light, state, plane, top0, p0, blockX, blockY, blockZ, level, verts);
            } else {
                v_BL = this.appendSkirtVertex(light, state, p0, bot0, plane, blockX, blockY, blockZ, level, verts);
                v_BR = this.appendSkirtVertex(light, state, p1, bot1, plane, blockX, blockY, blockZ, level, verts);
                v_TR = this.appendSkirtVertex(light, state, p1, top1, plane, blockX, blockY, blockZ, level, verts);
                v_TL = this.appendSkirtVertex(light, state, p0, top0, plane, blockX, blockY, blockZ, level, verts);
            }

            IndexWriter buf = indices[this.levelRenderer.renderTypeForState(state)];
            // Emit double-sided quad (both orientations) to guarantee 100% visibility from any camera angle
            this.appendQuad(buf, v_BL, v_BR, v_TL, v_TR);
            this.appendQuad(buf, v_TR, v_BR, v_TL, v_BL);

            wallsBuilt++;
            gaps.walls++;
        }

        SkirtDiag.recordSeam(new TilePos(level, blockX >> (level + T_SHIFT), blockY >> (level + T_SHIFT), blockZ >> (level + T_SHIFT)), xAxis, wallsBuilt, colLow, colHigh); //TEMP DIAG
    }

    private static void fillMissingFromNearest(int[] surfaces, int[] states, byte[] lights) {
        for (int i = 0; i < T_VOXELS; i++) {
            if (surfaces[i] < 0) {
                int bestDist = Integer.MAX_VALUE;
                int bestIdx = -1;
                for (int j = 0; j < T_VOXELS; j++) {
                    if (surfaces[j] >= 0) {
                        int dist = Math.abs(i - j);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestIdx = j;
                        }
                    }
                }
                if (bestIdx >= 0) {
                    surfaces[i] = surfaces[bestIdx];
                    if (states[i] == 0) {
                        states[i] = states[bestIdx];
                    }
                    if (lights[i] == 0) {
                        lights[i] = lights[bestIdx];
                    }
                }
            }
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
            int foliageY = -1;
            for (int y = T_VOXELS - 1; y >= 0; y--) {
                if (!tile.get(colX, y, colZ, out) || out.edges == 0) {
                    continue;
                }
                int state = surfaceState(out, 1);
                if (this.levelRenderer.isFoliage(state)) {
                    if (foliageY < 0) {
                        foliageY = y;
                    }
                    continue;
                }
                if (type < 0 || this.levelRenderer.renderTypeForState(state) == type) {
                    return y;
                }
            }
            if (foliageY >= 0) {
                tile.get(colX, foliageY, colZ, out);
                return foliageY;
            }
            return -1;
        }

        int foliageY = -1;
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
                    if (foliageY < 0) {
                        foliageY = y;
                    }
                    continue;
                }
                if (type < 0 || this.levelRenderer.renderTypeForState(state) == type) {
                    return y;
                }
            }
        }
        if (foliageY >= 0) {
            tile.get(colX, foliageY, colZ, out);
            return foliageY;
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

        int posX = gx == T_VOXELS ? (T_VOXELS << POS_FRACT_SHIFT) : (gz == T_VOXELS ? (gx << POS_FRACT_SHIFT) : (gx << POS_FRACT_SHIFT) + (data.x & fractMask));
        int posY = (gy << POS_FRACT_SHIFT) + (data.y & fractMask);
        int posZ = gz == T_VOXELS ? (T_VOXELS << POS_FRACT_SHIFT) : (gx == T_VOXELS ? (gz << POS_FRACT_SHIFT) : (gz << POS_FRACT_SHIFT) + (data.z & fractMask));

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
