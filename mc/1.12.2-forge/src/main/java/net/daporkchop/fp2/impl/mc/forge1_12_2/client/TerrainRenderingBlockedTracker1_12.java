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

package net.daporkchop.fp2.impl.mc.forge1_12_2.client;

import lombok.NonNull;
import net.daporkchop.fp2.core.client.FP2Client;
import net.daporkchop.fp2.core.client.render.TerrainRenderingBlockedTracker;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.at.client.renderer.ATRenderChunk1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.at.client.renderer.ATRenderGlobal1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.at.client.renderer.ATRenderGlobal__ContainerLocalRenderInformation1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.at.client.renderer.ATViewFrustum1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.compat.cc.FP2CubicChunks1_12;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static net.daporkchop.fp2.core.util.math.MathUtil.*;
import static net.daporkchop.lib.common.math.PMath.*;

/**
 * @author DaPorkchop_
 */
public class TerrainRenderingBlockedTracker1_12 extends TerrainRenderingBlockedTracker {
    protected static long visibilityFlags(CompiledChunk compiledChunk) {
        long flags = 0L;
        int shift = SHIFT_VISIBILITY;
        for (EnumFacing outFace : EnumFacing.VALUES) {
            for (EnumFacing inFace : EnumFacing.VALUES) {
                if (compiledChunk.isVisible(inFace, outFace)) {
                    flags |= 1L << shift;
                }
                shift++;
            }
        }
        return flags;
    }

    protected static long renderDirectionFlags(ATRenderGlobal__ContainerLocalRenderInformation1_12 containerLocalRenderInformation) {
        long flags = 0L;
        int shift = SHIFT_RENDER_DIRECTIONS;
        for (EnumFacing inFace : EnumFacing.VALUES) {
            if (containerLocalRenderInformation.invokeHasDirection(inFace)) {
                flags |= 1L << shift;
            }
            shift++;
        }
        return flags;
    }

    protected static long inFaceFlags(ATRenderGlobal__ContainerLocalRenderInformation1_12 containerLocalRenderInformation) {
        EnumFacing direction = containerLocalRenderInformation.getFacing();
        if (direction != null) {
            return (direction.ordinal() + 1L) << SHIFT_INFACE;
        } else { //direction is null, meaning this is the origin chunk section
            return 0L; //return an invalid face index, which will become negative when decoded
        }
    }

    public TerrainRenderingBlockedTracker1_12(FP2Client client) {
        super(client);
    }

    @Override
    protected int[] getExpectedFaceOffsets() {
        return Stream.of(EnumFacing.VALUES)
                .flatMapToInt(direction -> IntStream.of(direction.getXOffset(), direction.getYOffset(), direction.getZOffset()))
                .toArray();
    }

    /**
     * Updates this tracker instance based on the state of the given {@link RenderGlobal}.
     */
    public void update(@NonNull RenderGlobal renderGlobal, @NonNull ICamera camera, int frameCount) {
        //figure out the maximum extents of all of the renderChunks in the current ViewFrustum
        ViewFrustum viewFrustum = ((ATRenderGlobal1_12) renderGlobal).getViewFrustum();
        World world = ((ATViewFrustum1_12) viewFrustum).getWorld();
        boolean cubic = FP2CubicChunks1_12.isCubicWorld(world);
        int sizeX = ((ATViewFrustum1_12) viewFrustum).getCountChunksX();
        int sizeY = ((ATViewFrustum1_12) viewFrustum).getCountChunksY();
        int sizeZ = ((ATViewFrustum1_12) viewFrustum).getCountChunksZ();

        int renderPosX = floorI(((ATRenderGlobal1_12) renderGlobal).getFrustumUpdatePosX()) - 8;
        int renderPosY = floorI(((ATRenderGlobal1_12) renderGlobal).getFrustumUpdatePosY()) - 8;
        int renderPosZ = floorI(((ATRenderGlobal1_12) renderGlobal).getFrustumUpdatePosZ()) - 8;

        int minChunkX = asrCeil(renderPosX - (sizeX << 4 >> 1), 4);
        int maxChunkX = asrCeil(renderPosX + (sizeX << 4 >> 1), 4);
        int minChunkY;
        int maxChunkY;
        int minChunkZ = asrCeil(renderPosZ - (sizeZ << 4 >> 1), 4);
        int maxChunkZ = asrCeil(renderPosZ + (sizeZ << 4 >> 1), 4);

        if (cubic) {
            minChunkY = asrCeil(renderPosY - (sizeY << 4 >> 1), 4);
            maxChunkY = asrCeil(renderPosY + (sizeY << 4 >> 1), 4);
        } else {
            minChunkY = 0;
            maxChunkY = 16;
        }

        int offsetChunkX = -minChunkX;
        int offsetChunkY = -minChunkY;
        int offsetChunkZ = -minChunkZ;
        int factorChunkX = maxChunkX - minChunkX;
        int factorChunkY = maxChunkY - minChunkY;
        int factorChunkZ = maxChunkZ - minChunkZ;

        int playerChunkX = renderPosX >> 4;
        int playerChunkZ = renderPosZ >> 4;
        int renderDist = net.minecraft.client.Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
        int maxSafeDistSq = Math.max(0, renderDist - 1) * Math.max(0, renderDist - 1);
        int maxRenderDistSq = renderDist * renderDist;
        net.minecraft.entity.Entity viewEntity = net.minecraft.client.Minecraft.getMinecraft().getRenderViewEntity();
        int camChunkX = viewEntity != null ? floorI(viewEntity.posX) >> 4 : floorI(((ATRenderGlobal1_12) renderGlobal).getFrustumUpdatePosX()) >> 4;
        int camChunkY = viewEntity != null ? floorI(viewEntity.posY + viewEntity.getEyeHeight()) >> 4 : floorI(((ATRenderGlobal1_12) renderGlobal).getFrustumUpdatePosY()) >> 4;
        int camChunkZ = viewEntity != null ? floorI(viewEntity.posZ) >> 4 : floorI(((ATRenderGlobal1_12) renderGlobal).getFrustumUpdatePosZ()) >> 4;

        long[] srcFlags = new long[factorChunkX * factorChunkY * factorChunkZ];
        Chunk[] loadedColumns = cubic ? null : new Chunk[factorChunkX * factorChunkZ];
        boolean[] columnChecked = cubic ? null : new boolean[factorChunkX * factorChunkZ];
        //iterate over all the ContainerLocalRenderInformation instances, because they indicate:
        // - which direction to enter a RenderChunk from
        // - whether or not a RenderChunk is actually selected to be rendered
        ((ATRenderGlobal1_12) renderGlobal).getRenderInfos().forEach(containerLocalRenderInformation -> {
            BlockPos pos = containerLocalRenderInformation.getRenderChunk().getPosition();
            int chunkX = pos.getX() >> 4;
            int chunkY = pos.getY() >> 4;
            int chunkZ = pos.getZ() >> 4;

            assert chunkX >= minChunkX && chunkX < maxChunkX && chunkY >= minChunkY && chunkY < maxChunkY && chunkZ >= minChunkZ && chunkZ < maxChunkZ
                    : "RenderChunk(" + chunkX + ',' + chunkY + ',' + chunkZ + ") is outside of render distance: from (" + minChunkX + ',' + minChunkY + ',' + minChunkZ + ") to (" + maxChunkX + ',' + maxChunkY + ',' + maxChunkZ + ')';
            int gx = chunkX + offsetChunkX;
            int gy = chunkY + offsetChunkY;
            int gz = chunkZ + offsetChunkZ;
            if (gx < 0 || gx >= factorChunkX || gy < 0 || gy >= factorChunkY || gz < 0 || gz >= factorChunkZ) { //the render info list may briefly contain chunks outside of the grid, e.g. immediately after a render distance change - simply ignore those
                return;
            }
            int index = (gx * factorChunkY + gy) * factorChunkZ + gz;

            int dx = chunkX - playerChunkX;
            int dz = chunkZ - playerChunkZ;
            if (dx * dx + dz * dz > maxRenderDistSq) {
                // outer edge of render distance: conservative, don't mark as blocked to prevent gaps,
                //   but mark FLAG_VANILLA_SELECTED so inner neighbors know vanilla is rendering this chunk
                srcFlags[index] |= FLAG_VANILLA_SELECTED;
                return;
            }

            long flags = 0L;
            flags |= renderDirectionFlags(containerLocalRenderInformation);
            flags |= inFaceFlags(containerLocalRenderInformation);
            if (containerLocalRenderInformation.getFacing() == null
                    && (Math.abs(chunkX - camChunkX) > 1 || Math.abs(chunkY - camChunkY) > 1 || Math.abs(chunkZ - camChunkZ) > 1)) {
                flags |= FLAG_EXTERNAL_SEED;
            }

            flags |= FLAG_SELECTED | FLAG_VANILLA_SELECTED;
            srcFlags[index] |= flags;
        });

        //iterate over all the RenderChunks, because they indicate:
        // - whether or not a RenderChunk's frame index is up-to-date, which can be used to determine whether a RenderChunk would have been selected but failed the frustum check
        // - whether or not a RenderChunk has been baked
        // - the RenderChunk's neighbor visibility graph
        Stream.of(viewFrustum.renderChunks).forEach(renderChunk -> {
            long flags = 0L;

            if (((ATRenderChunk1_12) renderChunk).getFrameIndex() == frameCount) {
                flags |= FLAG_RENDERABLE;
            }

            if (camera.isBoundingBoxInFrustum(renderChunk.boundingBox)) {
                flags |= FLAG_INFRUSTUM;
            }

            CompiledChunk compiledChunk = renderChunk.getCompiledChunk();
            if (compiledChunk != CompiledChunk.DUMMY) {
                flags |= FLAG_BAKED;
                if (!compiledChunk.isEmpty()) {
                    flags |= FLAG_NONEMPTY;
                }
                flags |= visibilityFlags(compiledChunk);
            }

            BlockPos pos = renderChunk.getPosition();
            int chunkX = pos.getX() >> 4;
            int chunkY = pos.getY() >> 4;
            int chunkZ = pos.getZ() >> 4;
            assert chunkX >= minChunkX && chunkX < maxChunkX && chunkY >= minChunkY && chunkY < maxChunkY && chunkZ >= minChunkZ && chunkZ < maxChunkZ
                    : "RenderChunk(" + chunkX + ',' + chunkY + ',' + chunkZ + ") is outside of render distance: from (" + minChunkX + ',' + minChunkY + ',' + minChunkZ + ") to (" + maxChunkX + ',' + maxChunkY + ',' + maxChunkZ + ')';
            int gx = chunkX + offsetChunkX;
            int gy = chunkY + offsetChunkY;
            int gz = chunkZ + offsetChunkZ;
            if (gx >= 0 && gx < factorChunkX && gy >= 0 && gy < factorChunkY && gz >= 0 && gz < factorChunkZ) { //(see above)
                int dx = chunkX - playerChunkX;
                int dz = chunkZ - playerChunkZ;
                boolean inSafeDist = dx * dx + dz * dz <= maxSafeDistSq;
                if (inSafeDist) {
                    flags |= FLAG_IN_SAFE_DIST;
                }
                if (!cubic && inSafeDist && (compiledChunk == CompiledChunk.DUMMY || compiledChunk.isEmpty())) {
                    int colIdx = gx * factorChunkZ + gz;
                    if (!columnChecked[colIdx]) {
                        columnChecked[colIdx] = true;
                        loadedColumns[colIdx] = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
                    }
                    Chunk chunk = loadedColumns[colIdx];
                    if (chunk != null && !chunk.isEmpty()) {
                        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
                        if (chunkY >= 0 && chunkY < sections.length && (sections[chunkY] == null || sections[chunkY].isEmpty())) {
                            flags |= FLAG_VERIFIED_EMPTY;
                        }
                    }
                }
                int gridIdx = (gx * factorChunkY + gy) * factorChunkZ + gz;
                if (!cubic && inSafeDist
                        && (flags & (FLAG_BAKED | FLAG_RENDERABLE | FLAG_INFRUSTUM | FLAG_VERIFIED_EMPTY)) == (FLAG_BAKED | FLAG_RENDERABLE | FLAG_INFRUSTUM | FLAG_VERIFIED_EMPTY)
                        && (flags & FLAG_NONEMPTY) == 0L
                        && (srcFlags[gridIdx] & FLAG_SELECTED) == 0L) {
                    flags |= FLAG_SELECTED | FLAG_VANILLA_SELECTED | FLAG_EXTERNAL_SEED;
                }
                srcFlags[gridIdx] |= flags;
            }
        });

        //iterate over the whole grid again, setting the flag to indicate fp2 rendering is blocked by the RenderChunk at the given position if
        //  the following applies:
        //  - the RenderChunk has been compiled
        //  - all of the RenderChunk's visible neighbors have been compiled

        int scanMinX = 1;
        int scanMaxX = factorChunkX - 1;
        int scanMinY;
        int scanMaxY;
        int scanMinZ = 1;
        int scanMaxZ = factorChunkZ - 1;

        if (cubic) {
            scanMinY = 1;
            scanMaxY = factorChunkY - 1;
        } else {
            scanMinY = 0;
            scanMaxY = 16;
        }

        long addr = this.preTransformFlags(
                factorChunkX, factorChunkY, factorChunkZ,
                offsetChunkX, offsetChunkY, offsetChunkZ);

        transformFlags(
                scanMinX, scanMaxX, scanMinY, scanMaxY, scanMinZ, scanMaxZ,
                minChunkX, maxChunkX, minChunkY, maxChunkY, minChunkZ, maxChunkZ,
                factorChunkX, factorChunkY, factorChunkZ,
                offsetChunkX, offsetChunkY, offsetChunkZ,
                srcFlags, addr);

        this.postTransformFlags();
    }
}
