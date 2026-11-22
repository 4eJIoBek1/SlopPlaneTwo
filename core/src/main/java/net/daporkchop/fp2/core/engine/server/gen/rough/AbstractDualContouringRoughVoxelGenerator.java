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

package net.daporkchop.fp2.core.engine.server.gen.rough;

import lombok.NonNull;
import net.daporkchop.fp2.api.util.math.IntAxisAlignedBB;
import net.daporkchop.fp2.api.world.level.FBlockLevel;
import net.daporkchop.fp2.api.world.level.GenerationNotAllowedException;
import net.daporkchop.fp2.core.engine.Tile;
import net.daporkchop.fp2.core.engine.TileCoordLimits;
import net.daporkchop.fp2.core.engine.TileData;
import net.daporkchop.fp2.core.engine.TilePos;
import net.daporkchop.fp2.core.engine.api.server.IFarTileProvider;
import net.daporkchop.fp2.core.server.world.ExactFBlockLevelHolder;
import net.daporkchop.fp2.core.server.world.level.IFarLevelServer;
import net.daporkchop.fp2.core.util.math.Vector3d;
import net.daporkchop.fp2.core.util.math.qef.QefSolver;
import net.daporkchop.lib.common.reference.ReferenceStrength;
import net.daporkchop.lib.common.reference.cache.Cached;

import java.util.Arrays;

import static java.lang.Math.*;
import static net.daporkchop.fp2.api.world.level.BlockLevelConstants.*;
import static net.daporkchop.fp2.core.FP2Core.*;
import static net.daporkchop.fp2.core.debug.FP2Debug.*;
import static net.daporkchop.fp2.core.engine.EngineConstants.*;
import static net.daporkchop.fp2.core.util.math.MathUtil.*;
import static net.daporkchop.lib.common.math.PMath.*;
import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * @author DaPorkchop_
 */
public abstract class AbstractDualContouringRoughVoxelGenerator<PARAM> extends AbstractRoughVoxelGenerator {
    protected static final class BoundaryScratch {
        final boolean[] mask = new boolean[cb(CACHE_SIZE)];
        final int[] states = new int[cb(CACHE_SIZE)];
        final byte[] lights = new byte[cb(CACHE_SIZE)];
        final int[] tempStates = new int[T_VERTS * T_VERTS];
        final byte[] tempLights = new byte[T_VERTS * T_VERTS];
        final boolean[] exactNeighbors = new boolean[27];
    }

    protected final Cached<double[][]> densityMapCache = Cached.threadLocal(() -> new double[2][cb(CACHE_SIZE)], ReferenceStrength.WEAK);
    protected final Cached<BoundaryScratch> boundaryScratchCache = Cached.threadLocal(BoundaryScratch::new, ReferenceStrength.WEAK);

    public AbstractDualContouringRoughVoxelGenerator(@NonNull IFarLevelServer world, @NonNull IFarTileProvider provider) {
        super(world, provider);
    }

    protected byte[] populateTypeMapFromDensityMap(@NonNull double[][] densityMap) {
        byte[] typeMap = this.typeMapCache.get();
        double[] densityMap0 = densityMap[0];
        double[] densityMap1 = densityMap[1];

        //range check here to allow JIT to avoid range checking inside the loop
        checkArg(typeMap.length >= cb(CACHE_SIZE) && densityMap0.length >= cb(CACHE_SIZE) && densityMap1.length >= cb(CACHE_SIZE));

        for (int i = 0; i < cb(CACHE_SIZE); i++) { //set each type flag depending on the value in the corresponding density map layer
            byte type = 0;
            if (densityMap0[i] > 0.0d) {
                type |= BLOCK_TYPE_TRANSPARENT;
            }
            if (densityMap1[i] > 0.0d) {
                type |= BLOCK_TYPE_OPAQUE;
            }
            typeMap[i] = type;
        }

        return typeMap;
    }

    /**
     * Separates shallow submerged seabeds from the water surface when both cross zero on the same vertical grid edge.
     * <p>
     * In dual contouring, each voxel edge can represent at most one surface. When a vertical edge transitions directly
     * from {@code solid+liquid} (layer 3) to {@code air} (layer 0) and the water surface is higher than the terrain
     * surface, {@link #dualContour} has to emit water on that edge, which would otherwise drop the seabed completely and
     * expose the sky behind shallow water (breaking both vanilla translucency and shader-pack water depth fog).
     * <p>
     * Pushing the solid density at the lower node slightly negative (when solid ground exists at {@code y - 1}) turns
     * that node into {@code liquid}-only (layer 1), keeping the water surface on {@code [y, y+1]} while moving the seabed
     * continuously onto {@code [y-1, y]} directly underneath (and stitching it to dry shores via horizontal {@code 1 <-> 3}
     * edges). Only strictly interior {@code y} indices ({@code 1 .. T_VOXELS - 1}) are adjusted so vertical tile boundaries
     * ({@code y = 0} / {@code y = T_VOXELS}) always agree across adjacent tiles and horizontal boundaries match identically.
     */
    protected void separateShallowWaterFromSeabed(@NonNull double[][] densityMap) {
        double[] water = densityMap[0];
        double[] block = densityMap[1];

        checkArg(water.length >= cb(CACHE_SIZE) && block.length >= cb(CACHE_SIZE));

        for (int x = 0; x <= T_VOXELS; x++) {
            for (int y = 1; y < T_VOXELS; y++) {
                int rowBase = cacheIndex(x, y, 0);
                for (int z = 0; z <= T_VOXELS; z++) {
                    int idx = rowBase + z;
                    double w0 = water[idx];
                    if (w0 <= 0.0d) {
                        continue;
                    }
                    double w1 = water[idx + CACHE_SIZE];
                    if (w1 > 0.0d) {
                        continue;
                    }

                    double b0 = block[idx];
                    if (b0 <= 0.0d) {
                        continue;
                    }
                    double b1 = block[idx + CACHE_SIZE];
                    if (b1 > 0.0d) {
                        continue;
                    }

                    if (minimize(w0, w1) > minimize(b0, b1) && block[idx - CACHE_SIZE] > 0.0d) {
                        block[idx] = -1e-4d;
                    }
                }
            }
        }
    }

    /**
     * Checks whether the 4 chunks needed by {@link net.daporkchop.fp2.core.engine.server.gen.exact.AbstractExactVoxelGenerator}
     * for columnar tile column {@code (nx, nz)} are populated and usable without generating anything.
     */
    private boolean areColumnChunksUsable(
            @NonNull FBlockLevel exactWorld,
            @NonNull IntAxisAlignedBB limits,
            int nx, int nz) {
        int minY = limits.minY();
        int maxY = limits.maxY() - 1;
        if (minY > maxY) {
            return false;
        }
        int bx = nx << T_SHIFT;
        int bz = nz << T_SHIFT;
        int x0 = bx - 2;
        int x1 = bx + T_VOXELS - 2;
        int z0 = bz - 2;
        int z1 = bz + T_VOXELS - 2;
        try {
            exactWorld.getState(x0, minY, z0);
            exactWorld.getState(x1, minY, z0);
            exactWorld.getState(x0, minY, z1);
            exactWorld.getState(x1, minY, z1);
            return true;
        } catch (GenerationNotAllowedException e) {
            return false;
        }
    }

    /**
     * Checks whether neighbor tile {@code (0, nx, ny, nz)} has all required exact data available without generating
     * new chunks or cubes (matching {@link TileWorker}'s exact vs rough decision).
     */
    private boolean isExactNeighbor(
            @NonNull FBlockLevel exactWorld,
            @NonNull TileCoordLimits coordLimits,
            @NonNull IntAxisAlignedBB limits,
            int nx, int ny, int nz) {
        if (!coordLimits.contains(new TilePos(0, nx, ny, nz))) {
            return false;
        }
        int bx = nx << T_SHIFT;
        int by = ny << T_SHIFT;
        int bz = nz << T_SHIFT;
        if (!exactWorld.containsAnyData(bx, by, bz, bx + T_VOXELS, by + T_VOXELS, bz + T_VOXELS)) {
            return false;
        }
        int minY = limits.minY();
        int maxY = limits.maxY() - 1;
        if (minY > maxY) {
            return false;
        }
        int x0 = bx - 2;
        int x1 = bx + T_VOXELS - 2;
        int z0 = bz - 2;
        int z1 = bz + T_VOXELS - 2;
        int y0 = clamp(by - 2, minY, maxY);
        int y1 = clamp(by + T_VOXELS - 2, minY, maxY);
        try {
            exactWorld.getState(x0, y0, z0);
            exactWorld.getState(x1, y0, z0);
            exactWorld.getState(x0, y0, z1);
            exactWorld.getState(x1, y0, z1);
            if ((y0 >> T_SHIFT) != (y1 >> T_SHIFT)) {
                exactWorld.getState(x0, y1, z0);
                exactWorld.getState(x1, y1, z0);
                exactWorld.getState(x0, y1, z1);
                exactWorld.getState(x1, y1, z1);
            }
            return true;
        } catch (GenerationNotAllowedException e) {
            return false;
        }
    }

    private static boolean hasExact(@NonNull boolean[] exactNeighbors, int ox, int oy, int oz) {
        return exactNeighbors[((ox + 1) * 3 + (oy + 1)) * 3 + (oz + 1)];
    }

    /**
     * Overlays exact block types, states, and light levels onto the shared lattice planes/edges/corners of a level-0
     * rough tile wherever an adjacent tile generated via {@code AbstractExactVoxelGenerator}.
     * <p>
     * Because exact generation reads {@code Block(X - 1, Y - 1, Z - 1)} at global lattice point {@code (X, Y, Z)},
     * overriding those exact shared lattice points with sign-preserving densities forces the boundary {@code tMap} and
     * emitted boundary quads to match the neighboring exact tile bit-for-bit without disturbing interior rough voxels.
     *
     * @return {@code true} if at least one shared lattice point was stitched to exact terrain
     */
    protected boolean stitchExactBoundaries(int baseX, int baseY, int baseZ, @NonNull double[][] densityMap, @NonNull BoundaryScratch scratch) {
        if (FP2_DEBUG && !fp2().globalConfig().debug().exactGeneration()) {
            return false;
        }

        try (FBlockLevel exactWorld = this.world().exactBlockLevelHolder().worldFor(ExactFBlockLevelHolder.AllowGenerationRequirement.NOT_ALLOWED)) {
            if (!exactWorld.containsAnyData(
                    baseX - T_VOXELS, baseY - T_VOXELS, baseZ - T_VOXELS,
                    baseX + (T_VOXELS << 1), baseY + (T_VOXELS << 1), baseZ + (T_VOXELS << 1))) {
                return false;
            }

            int tx = baseX >> T_SHIFT;
            int ty = baseY >> T_SHIFT;
            int tz = baseZ >> T_SHIFT;
            IntAxisAlignedBB limits = exactWorld.dataLimits();
            TileCoordLimits coordLimits = this.provider().coordLimits();
            boolean columnar = limits.minY() == 0 && limits.maxY() <= 256;

            int colMask = 0;
            int colChecked = 0;
            boolean anyExact = false;
            Arrays.fill(scratch.exactNeighbors, false);

            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        if (ox == 0 && oy == 0 && oz == 0) {
                            continue;
                        }
                        int nx = tx + ox;
                        int ny = ty + oy;
                        int nz = tz + oz;
                        boolean ok;
                        if (columnar) {
                            if (!coordLimits.contains(new TilePos(0, nx, ny, nz))
                                    || !exactWorld.containsAnyData(nx << T_SHIFT, ny << T_SHIFT, nz << T_SHIFT,
                                    (nx + 1) << T_SHIFT, (ny + 1) << T_SHIFT, (nz + 1) << T_SHIFT)) {
                                continue;
                            }
                            int colBit = 1 << ((ox + 1) * 3 + (oz + 1));
                            if ((colChecked & colBit) == 0) {
                                colChecked |= colBit;
                                if (this.areColumnChunksUsable(exactWorld, limits, nx, nz)) {
                                    colMask |= colBit;
                                }
                            }
                            ok = (colMask & colBit) != 0;
                        } else {
                            ok = this.isExactNeighbor(exactWorld, coordLimits, limits, nx, ny, nz);
                        }
                        if (ok) {
                            scratch.exactNeighbors[((ox + 1) * 3 + (oy + 1)) * 3 + (oz + 1)] = true;
                            anyExact = true;
                        }
                    }
                }
            }

            if (!anyExact) {
                return false;
            }

            Arrays.fill(scratch.mask, false);
            double[] water = densityMap[0];
            double[] block = densityMap[1];
            boolean anyMasked = false;

            for (int dist = 1; dist <= 3; dist++) {
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            if (abs(ox) + abs(oy) + abs(oz) != dist
                                    || !hasExact(scratch.exactNeighbors, ox, oy, oz)) {
                                continue;
                            }
                            if (dist == 2) {
                                if ((ox != 0 && hasExact(scratch.exactNeighbors, ox, 0, 0))
                                        || (oy != 0 && hasExact(scratch.exactNeighbors, 0, oy, 0))
                                        || (oz != 0 && hasExact(scratch.exactNeighbors, 0, 0, oz))) {
                                    continue;
                                }
                            } else if (dist == 3) {
                                if (hasExact(scratch.exactNeighbors, ox, 0, 0)
                                        || hasExact(scratch.exactNeighbors, 0, oy, 0)
                                        || hasExact(scratch.exactNeighbors, 0, 0, oz)
                                        || hasExact(scratch.exactNeighbors, ox, oy, 0)
                                        || hasExact(scratch.exactNeighbors, ox, 0, oz)
                                        || hasExact(scratch.exactNeighbors, 0, oy, oz)) {
                                    continue;
                                }
                            }

                            int uMin = ox == 1 ? T_VOXELS : 0;
                            int uMax = ox == -1 ? 0 : T_VOXELS;
                            int vMin = oy == 1 ? T_VOXELS : 0;
                            int vMax = oy == -1 ? 0 : T_VOXELS;
                            int wMin = oz == 1 ? T_VOXELS : 0;
                            int wMax = oz == -1 ? 0 : T_VOXELS;

                            int uSize = uMax - uMin + 1;
                            int vSize = vMax - vMin + 1;
                            int wSize = wMax - wMin + 1;

                            try {
                                exactWorld.multiGetDense(
                                        baseX + uMin - 1, baseY + vMin - 1, baseZ + wMin - 1,
                                        uSize, vSize, wSize,
                                        scratch.tempStates, 0, null, 0, scratch.tempLights, 0);
                            } catch (GenerationNotAllowedException e) {
                                scratch.exactNeighbors[((ox + 1) * 3 + (oy + 1)) * 3 + (oz + 1)] = false;
                                continue;
                            }

                            for (int du = 0; du < uSize; du++) {
                                for (int dv = 0; dv < vSize; dv++) {
                                    for (int dw = 0; dw < wSize; dw++) {
                                        int idx = cacheIndex(uMin + du, vMin + dv, wMin + dw);
                                        if (scratch.mask[idx]) {
                                            continue;
                                        }
                                        int qIdx = (du * vSize + dv) * wSize + dw;
                                        int state = scratch.tempStates[qIdx];
                                        byte light = scratch.tempLights[qIdx];
                                        byte blockType = (byte) this.extendedStateRegistryData().type(state);

                                        if (blockType == BLOCK_TYPE_OPAQUE) {
                                            if (block[idx] <= 0.0d) {
                                                block[idx] = 1.0d;
                                            }
                                            if (water[idx] >= 0.0d) {
                                                water[idx] = -1.0d;
                                            }
                                        } else if (blockType == BLOCK_TYPE_TRANSPARENT) {
                                            if (water[idx] <= 0.0d) {
                                                water[idx] = 1.0d;
                                            }
                                            if (block[idx] >= 0.0d) {
                                                block[idx] = -1.0d;
                                            }
                                        } else {
                                            if (water[idx] >= 0.0d) {
                                                water[idx] = -1.0d;
                                            }
                                            if (block[idx] >= 0.0d) {
                                                block[idx] = -1.0d;
                                            }
                                        }

                                        scratch.mask[idx] = true;
                                        scratch.states[idx] = state;
                                        scratch.lights[idx] = light;
                                        anyMasked = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return anyMasked;
        }
    }

    protected void dualContour(int baseX, int baseY, int baseZ, int level, Tile tile, double[][] densityMap, PARAM param) {
        QefSolver qef = new QefSolver();
        TileData data = new TileData();
        Vector3d vec = new Vector3d();

        BoundaryScratch boundary = null;
        boolean hasExactBoundary = false;
        if (level == 0) {
            boundary = this.boundaryScratchCache.get();
            hasExactBoundary = this.stitchExactBoundaries(baseX, baseY, baseZ, densityMap, boundary);
        }

        //push shallow submerged seabed crossings down one cell so water and seabed don't collide on the same vertical edge
        this.separateShallowWaterFromSeabed(densityMap);

        //use bit flags to identify voxel types rather than reading from the density map each time to keep innermost loop head tight and cache-friendly
        byte[] tMap = this.populateTypeMapFromDensityMap(densityMap);

        for (int dx = 0; dx < T_VOXELS; dx++) {
            for (int dy = 0; dy < T_VOXELS; dy++) {
                for (int dz = 0; dz < T_VOXELS; dz++) {
                    int diBase = cacheIndex(dx, dy, dz);

                    //check for intersection data for each corner
                    int corners = 0;
                    for (int i = 0; i < 8; i++) {
                        int di = diBase + CACHE_INDEX_ADD[i];
                        corners |= (tMap[di] & 0xFF) << (i << 1);
                    }

                    if (corners == 0 || corners == 0x5555 || corners == 0xAAAA || corners == 0xFFFF) { //if all corners are the same type, this voxel can be safely skipped
                        continue;
                    }

                    boolean pinToExactCorner = hasExactBoundary && boundary.mask[diBase];

                    double totalNx = 0.0d;
                    double totalNy = 0.0d;
                    double totalNz = 0.0d;
                    int exactAirSkyLight = 0;
                    int exactAirBlockLight = 0;
                    int exactAirLightSamples = 0;

                    //populate the QEF with data
                    qef.reset();
                    double waterYSum = 0.0d;
                    int waterYCount = 0;
                    int edgeCount = 0;
                    int edges = 0;
                    for (int edge = 0; edge < QEF_EDGE_COUNT; edge++) {
                        int c0 = QEF_EDGE_VERTEX_MAP[edge << 1];
                        int c1 = QEF_EDGE_VERTEX_MAP[(edge << 1) | 1];

                        //determine which layer the two corners that make up this edge are on
                        int layer0 = (corners >> (c0 << 1)) & 3;
                        int layer1 = (corners >> (c1 << 1)) & 3;
                        if (layer0 == layer1 //both corners along the current edge are identical, this edge can be skipped
                            || max(layer0, layer1) == 3 && min(layer0, layer1) == 2) { //don't consider edges that transition from solid+liquid to solid, because then we'll be
                            // generating tons of internal mesh for no reason
                            continue;
                        }

                        int idx0 = diBase + CACHE_INDEX_ADD[c0];
                        int idx1 = diBase + CACHE_INDEX_ADD[c1];

                        //the connection is normally made on the bottommost layer...
                        int layer;
                        if (max(layer0, layer1) == 3 && min(layer0, layer1) == 0) {
                            //...however, an edge spanning from solid+liquid to air can cross both the water surface and the
                            //  terrain surface. in that case we pick whichever surface is higher up, as that's the one which
                            //  would be visible from above. using the block layer unconditionally here would swallow the water
                            //  surface wherever shallow terrain pokes into the topmost water layer, leaving holes in the sea.
                            double waterDensity0 = densityMap[0][idx0];
                            double waterDensity1 = densityMap[0][idx1];
                            double blockDensity0 = densityMap[1][idx0];
                            double blockDensity1 = densityMap[1][idx1];
                            if (waterDensity0 > 0.0d != waterDensity1 > 0.0d && blockDensity0 > 0.0d != blockDensity1 > 0.0d) {
                                //for the vertical edges (which are the only ones where the water flags can differ) c0 is always
                                //  the bottom corner and c1 the top one, so a larger value of t means a higher surface
                                layer = minimize(waterDensity0, waterDensity1) > minimize(blockDensity0, blockDensity1) ? 0 : 1;
                            } else {
                                layer = min(max(layer0, layer1) - 1, 1);
                            }
                        } else {
                            layer = min(max(layer0, layer1) - 1, 1);
                        }

                        //compute gradients in the current voxel on the chosen layer
                        double densityBase = densityMap[layer][diBase + CACHE_INDEX_ADD[0]];
                        double nx = densityMap[layer][diBase + CACHE_INDEX_ADD[4]] - densityBase;
                        double ny = densityMap[layer][diBase + CACHE_INDEX_ADD[2]] - densityBase;
                        double nz = densityMap[layer][diBase + CACHE_INDEX_ADD[1]] - densityBase;
                        totalNx += nx;
                        totalNy += ny;
                        totalNz += nz;

                        //get density values at both corners
                        double density0 = densityMap[layer][idx0];
                        double density1 = densityMap[layer][idx1];

                        boolean exact0 = hasExactBoundary && boundary.mask[idx0];
                        boolean exact1 = hasExactBoundary && boundary.mask[idx1];

                        //minimize the density function along the edge to find the point where it crosses 0
                        double t = (exact0 && exact1) ? 0.0d : clamp(minimize(density0, density1), 0.0d, 1.0d);
                        double px = lerp((c0 >> 2) & 1, (c1 >> 2) & 1, t);
                        double py = lerp((c0 >> 1) & 1, (c1 >> 1) & 1, t);
                        double pz = lerp(c0 & 1, c1 & 1, t);
                        if (layer == 0 && (edge >> 2) == 1
                                && (!(exact0 && exact1) || this.extendedStateRegistryData().isWater(boundary.states[layer0 < layer1 ? idx1 : idx0]))) {
                            //vertical water surface edge (QEF_EDGE_VERTEX_MAP edges 4..7)
                            waterYSum += py;
                            waterYCount++;
                        }

                        //add the edge crossing point to the QEF
                        qef.add(px, py, pz, nx, ny, nz);
                        edgeCount++;

                        if ((edge & 3) == 3) { //this is a renderable edge, so we need to set the state and face direction
                            int faceEdge = edge >> 2;
                            if (layer0 < layer1) { //the face is facing towards negative coordinates
                                edges |= EDGE_DIR_NEGATIVE << (faceEdge << 1);
                            } else {
                                edges |= EDGE_DIR_POSITIVE << (faceEdge << 1);
                            }

                            int solidIdx = layer0 < layer1 ? idx1 : idx0;
                            int airIdx = layer0 < layer1 ? idx0 : idx1;
                            if (hasExactBoundary && boundary.mask[airIdx]) {
                                byte pl = boundary.lights[airIdx];
                                exactAirSkyLight += unpackSkyLight(pl);
                                exactAirBlockLight += unpackBlockLight(pl);
                                exactAirLightSamples++;
                            }

                            if (hasExactBoundary && boundary.mask[solidIdx] && boundary.states[solidIdx] != 0) {
                                data.states[faceEdge] = boundary.states[solidIdx];
                            } else {
                                double fnx = nx;
                                double fny = ny;
                                double fnz = nz;
                                if (pinToExactCorner) {
                                    if (faceEdge == 1) {
                                        fnx = 0.0d;
                                        fny = density1 - density0;
                                        fnz = 0.0d;
                                    } else if (faceEdge == 0) {
                                        fnx = density1 - density0;
                                    } else {
                                        fnz = density1 - density0;
                                    }
                                }
                                data.states[faceEdge] = this.getFaceState(baseX + (dx << level), baseY + (dy << level), baseZ + (dz << level), level, fnx, fny, fnz, density0, density1, faceEdge, layer, param);
                            }
                        }
                    }

                    //yet another sanity check: a few voxels will make it through the check before the QEF initialization loop (specifically opaque+transparent -> opaque transitions),
                    // so provide the option to break out here without setting the voxel if we can
                    if (edgeCount == 0) {
                        continue;
                    }

                    data.edges = edges;

                    if (pinToExactCorner) {
                        //align vertices whose corner sits on a stitched exact boundary identically with the exact generator
                        data.x = 0;
                        data.y = 0;
                        data.z = 0;
                    } else {
                        //solve QEF and set the tile data
                        qef.solve(vec, 0.1d, 1, 0.5d);
                        if (!(vec.x >= 0.0d && vec.x <= 1.0d
                                && vec.y >= 0.0d && vec.y <= 1.0d
                                && vec.z >= 0.0d && vec.z <= 1.0d)) { //ensure that all points are within voxel bounds, and catches NaN!
                            //if not, fall back to the mass point (basically the average position of all edge intersections), which is basically guaranteed to be within the voxel bounds
                            vec.set(qef.massPoint().x, qef.massPoint().y, qef.massPoint().z);
                        }
                        if (waterYCount > 0) {
                            //keep every voxel touching a vertical water surface edge strictly on the water plane so shoreline water
                            //  quads remain perfectly horizontal (normal = (0, 1, 0)) instead of tilting toward the shore QEF
                            vec.y = waterYSum / waterYCount;
                        }

                        data.x = clamp(floorI(vec.x * POS_ONE), 0, POS_ONE);
                        data.y = clamp(floorI(vec.y * POS_ONE), 0, POS_ONE);
                        data.z = clamp(floorI(vec.z * POS_ONE), 0, POS_ONE);
                    }

                    //normalize normal vector safely
                    double nLenSq = totalNx * totalNx + totalNy * totalNy + totalNz * totalNz;
                    if (nLenSq > 1e-12d) {
                        double nFactor = 1.0d / sqrt(nLenSq);
                        totalNx *= nFactor;
                        totalNy *= nFactor;
                        totalNz *= nFactor;
                    } else {
                        totalNx = 0.0d;
                        totalNy = -1.0d;
                        totalNz = 0.0d;
                    }

                    this.populateVoxelBlockData(baseX + (dx << level), baseY + (dy << level), baseZ + (dz << level), level, totalNx, totalNy, totalNz, data, param);

                    if (hasExactBoundary) {
                        if (exactAirLightSamples > 0) {
                            data.light = packLight(exactAirSkyLight / exactAirLightSamples, exactAirBlockLight / exactAirLightSamples);
                        } else if (edges == 0 && pinToExactCorner) {
                            int minType = BLOCK_TYPE_OPAQUE;
                            for (int i = 0; i < 8; i++) {
                                minType = min(minType, (corners >> (i << 1)) & 3);
                            }
                            int sky = 0;
                            int blockL = 0;
                            int samples = 0;
                            for (int i = 0; i < 8; i++) {
                                int di = diBase + CACHE_INDEX_ADD[i];
                                if (((corners >> (i << 1)) & 3) == minType && boundary.mask[di]) {
                                    byte pl = boundary.lights[di];
                                    sky += unpackSkyLight(pl);
                                    blockL += unpackBlockLight(pl);
                                    samples++;
                                }
                            }
                            if (samples > 0) {
                                data.light = packLight(sky / samples, blockL / samples);
                            }
                        }
                    }

                    tile.set(dx, dy, dz, data);
                }
            }
        }

        tile.extra(0L); //TODO: compute neighbor connections
    }

    protected abstract int getFaceState(int blockX, int blockY, int blockZ, int level, double nx, double ny, double nz, double density0, double density1, int edge, int layer, PARAM param);

    protected abstract void populateVoxelBlockData(int blockX, int blockY, int blockZ, int level, double nx, double ny, double nz, TileData data, PARAM param);
}
