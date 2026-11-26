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

package net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.exactfblocklevel;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import net.daporkchop.fp2.api.event.FEventHandler;
import net.daporkchop.fp2.core.minecraft.util.threading.asynccache.AsyncCacheNBT;
import net.daporkchop.fp2.core.minecraft.world.chunks.AbstractChunksExactFBlockLevelHolder;
import net.daporkchop.fp2.core.minecraft.world.chunks.AbstractPrefetchedChunksExactFBlockLevel;
import net.daporkchop.fp2.core.server.event.ColumnSavedEvent;
import net.daporkchop.fp2.core.server.event.TickEndEvent;
import net.daporkchop.fp2.core.server.world.ExactFBlockLevelHolder;
import net.daporkchop.fp2.core.server.world.level.IFarLevelServer;
import net.daporkchop.fp2.core.util.datastructure.Datastructures;
import net.daporkchop.fp2.core.util.datastructure.NDimensionalIntSegtreeSet;
import net.daporkchop.fp2.core.util.threading.futurecache.IAsyncCache;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.at.world.chunk.storage.ATAnvilChunkLoader1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.asm.interfaz.world.IMixinWorldServer1_12;
import net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.region.ThreadSafeRegionFileCache;
import net.daporkchop.fp2.impl.mc.forge1_12_2.server.world.level.FLevelServer1_12;
import net.daporkchop.lib.common.function.exception.ERunnable;
import net.daporkchop.lib.common.math.BinMath;
import net.daporkchop.lib.math.vector.Vec2i;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.gen.ChunkProviderServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static net.daporkchop.fp2.core.FP2Core.*;
import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * Default implementation of {@link ExactFBlockLevelHolder} for vanilla worlds.
 *
 * @author DaPorkchop_
 */
public class VanillaExactFBlockLevelHolder1_12 extends AbstractChunksExactFBlockLevelHolder<Chunk> {
    private static final int MAX_CHUNKS_SYNCED_PER_PASS = 24;

    private final WorldServer mcWorld;
    private final AnvilChunkLoader chunkLoader;
    private final Long2ByteMap syncedPopulatedMask = new Long2ByteOpenHashMap();

    public VanillaExactFBlockLevelHolder1_12(@NonNull FLevelServer1_12 level) {
        super(level, 4);
        this.mcWorld = (WorldServer) level.implLevel();
        this.chunkLoader = (AnvilChunkLoader) this.mcWorld.getChunkProvider().chunkLoader;
    }

    private static boolean isLoadedPopulated(@NonNull ChunkProviderServer provider, int cx, int cz) {
        Chunk c = provider.loadedChunks.get(ChunkPos.asLong(cx, cz));
        return c != null && !c.isEmpty() && c.isTerrainPopulated();
    }

    private static byte computePopulatedMask(@NonNull ChunkProviderServer provider, int cx, int cz) {
        int mask = 1;
        if (isLoadedPopulated(provider, cx - 1, cz)) {
            mask |= 2;
        }
        if (isLoadedPopulated(provider, cx, cz - 1)) {
            mask |= 4;
        }
        if (isLoadedPopulated(provider, cx - 1, cz - 1)) {
            mask |= 8;
        }
        return (byte) mask;
    }

    @Override
    protected void onColumnSaved(@NonNull ColumnSavedEvent event) {
        super.onColumnSaved(event);

        if (event.column().isFullyPopulated()) {
            long packedPos = BinMath.packXY(event.pos().x(), event.pos().y());
            synchronized (this.syncedPopulatedMask) {
                this.syncedPopulatedMask.put(packedPos, (byte) (this.syncedPopulatedMask.get(packedPos) | 1));
            }
        }
    }

    private static long minChunkDistSq(@NonNull Chunk chunk, @NonNull List<EntityPlayer> players) {
        if (players.isEmpty()) {
            return 0L;
        }
        long best = Long.MAX_VALUE;
        for (int i = 0, n = players.size(); i < n; i++) {
            EntityPlayer player = players.get(i);
            long dx = (long) chunk.x - (((int) Math.floor(player.posX)) >> 4);
            long dz = (long) chunk.z - (((int) Math.floor(player.posZ)) >> 4);
            long distSq = dx * dx + dz * dz;
            if (distSq < best) {
                best = distSq;
            }
        }
        return best;
    }

    @FEventHandler
    private void onTickEnd(TickEndEvent event) {
        if (this.mcWorld.disableLevelSaving) {
            return;
        }

        boolean hasSkyLight = this.mcWorld.provider.hasSkyLight();
        int height = this.mcWorld.provider.getActualHeight();
        ChunkProviderServer chunkProvider = this.mcWorld.getChunkProvider();
        List<Chunk> candidates = null;

        for (Chunk chunk : chunkProvider.getLoadedChunks()) {
            if (chunk.isEmpty() || !chunk.isTerrainPopulated()) {
                continue;
            }
            if (!hasSkyLight && (!chunk.isLightPopulated() || ChunkBlockLightFixer1_12.needsRelight(chunk, height))) {
                continue;
            }

            byte popMask = computePopulatedMask(chunkProvider, chunk.x, chunk.z);
            long packedPos = BinMath.packXY(chunk.x, chunk.z);
            synchronized (this.syncedPopulatedMask) {
                byte prevMask = this.syncedPopulatedMask.get(packedPos);
                if ((popMask & ~prevMask) == 0) {
                    continue;
                }
            }

            if (!chunk.needsSaving(false) && this.chunksExistIndex().contains(chunk.x, chunk.z)) {
                synchronized (this.syncedPopulatedMask) {
                    this.syncedPopulatedMask.put(packedPos, (byte) (this.syncedPopulatedMask.get(packedPos) | popMask));
                }
                continue;
            }

            if (candidates == null) {
                candidates = new ArrayList<>();
            }
            candidates.add(chunk);
        }

        if (candidates == null) {
            return;
        }

        if (candidates.size() > 1) {
            List<EntityPlayer> players = this.mcWorld.playerEntities;
            candidates.sort((a, b) -> {
                int d = Long.compare(minChunkDistSq(a, players), minChunkDistSq(b, players));
                if (d == 0 && (d = Integer.compare(a.x, b.x)) == 0) {
                    d = Integer.compare(a.z, b.z);
                }
                return d;
            });
        }

        int limit = Math.min(candidates.size(), MAX_CHUNKS_SYNCED_PER_PASS);
        for (int i = 0; i < limit; i++) {
            Chunk chunk = candidates.get(i);
            long packedPos = BinMath.packXY(chunk.x, chunk.z);
            byte popMask = computePopulatedMask(chunkProvider, chunk.x, chunk.z);
            try {
                //AnvilChunkLoader.saveChunk serializes NBT, fires ChunkDataEvent.Save for immediate FP2 sync, and queues async disk I/O
                this.chunkLoader.saveChunk(this.mcWorld, chunk);
                synchronized (this.syncedPopulatedMask) {
                    this.syncedPopulatedMask.put(packedPos, (byte) (this.syncedPopulatedMask.get(packedPos) | popMask));
                }
            } catch (IOException | MinecraftException e) {
                fp2().log().warn("Failed to sync newly populated chunk (%d, %d) to FP2", e, chunk.x, chunk.z);
            }
        }
    }

    @Override
    protected NDimensionalIntSegtreeSet createChunksExistIndex(@NonNull IFarLevelServer level) {
        AnvilChunkLoader io = (AnvilChunkLoader) ((WorldServer) level.implLevel()).getChunkProvider().chunkLoader;
        return Datastructures.INSTANCE.nDimensionalIntSegtreeSet()
                .dimensions(2)
                .threadSafe(true)
                .initialPoints(() -> ThreadSafeRegionFileCache.INSTANCE.allChunks(io.chunkSaveLocation.toPath().resolve("region"))
                        .map(pos -> new int[]{ pos.x, pos.z })
                        .parallel())
                .build();
    }

    @Override
    protected AsyncCacheNBT<Vec2i, ?, Chunk, ?> createChunkCache(@NonNull IFarLevelServer level) {
        return new ChunkCache((WorldServer) level.implLevel(), (AnvilChunkLoader) ((WorldServer) level.implLevel()).getChunkProvider().chunkLoader);
    }

    @Override
    protected AbstractPrefetchedChunksExactFBlockLevel<Chunk> prefetchedWorld(boolean generationAllowed, @NonNull List<Chunk> chunks) {
        return new PrefetchedChunksFBlockLevel1_12(this, generationAllowed, chunks);
    }

    /**
     * {@link IAsyncCache} for chunks.
     *
     * @author DaPorkchop_
     */
    //TODO: this doesn't handle the difference between "chunk is populated" and "chunk and its neighbors are populated", which is important because vanilla is very dumb
    @RequiredArgsConstructor
    @Getter
    protected static class ChunkCache extends AsyncCacheNBT<Vec2i, Object, Chunk, NBTTagCompound> {
        @NonNull
        private final WorldServer world;
        @NonNull
        private final AnvilChunkLoader io;

        @Override
        protected Chunk parseNBT(@NonNull Vec2i key, @NonNull Object param, @NonNull NBTTagCompound nbt) {
            Chunk chunk = ((ATAnvilChunkLoader1_12) this.io).invokeCheckedReadChunkFromNBT(this.world, key.x(), key.y(), nbt);
            return chunk != null && this.isUsable(chunk) ? chunk : null;
        }

        @Override
        @SneakyThrows(IOException.class)
        protected Chunk loadFromDisk(@NonNull Vec2i key, @NonNull Object param) {
            Object[] data = this.io.loadChunk__Async(this.world, key.x(), key.y());
            Chunk chunk = data != null ? (Chunk) data[0] : null;
            return chunk != null && this.isUsable(chunk) ? chunk : null;
        }

        /**
         * Checks whether the given chunk contains all of the data needed to generate terrain from it.
         *
         * <p>Chunks in dimensions without skylight which were generated by fp2 are flagged as being lit even though their block
         * light was never actually computed (see {@link ChunkBlockLightFixer1_12}). Such chunks are rejected here so that
         * {@link #triggerGeneration(Vec2i, Object)} gets a chance to relight them - otherwise they would be rendered without
         * any light at all.</p>
         */
        private boolean isUsable(@NonNull Chunk chunk) {
            if (!chunk.isTerrainPopulated()) {
                return false;
            }
            if (this.world.provider.hasSkyLight()) {
                return true;
            }
            return chunk.isLightPopulated()
                    && !ChunkBlockLightFixer1_12.needsRelight(chunk, this.world.provider.getActualHeight());
        }

        @Override
        protected void triggerGeneration(@NonNull Vec2i key, @NonNull Object param) {
            ((IMixinWorldServer1_12) this.world).fp2_levelServer().workerManager().workExecutor().run((ERunnable) () -> {
                int x = key.x();
                int z = key.y();

                //see net.minecraftforge.server.command.ChunkGenWorker#doWork() for more info

                Chunk chunk = this.world.getChunk(x, z);
                this.world.getChunk(x + 1, z);
                this.world.getChunk(x + 1, z + 1);
                this.world.getChunk(x, z + 1);

                checkState(chunk.isTerrainPopulated(), "chunk at (%d, %d) couldn't be populated!", x, z);

                //make sure that the chunk is fully lit
                if (!chunk.isLightPopulated()) {
                    //try to light the chunk without loading any additional chunks
                    chunk.checkLight();

                    if (!chunk.isLightPopulated()) {
                        //load all the chunks all around the chunk, in the hopes that that will be enough
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                this.world.getChunk(x + dx, z + dz);
                            }
                        }
                        chunk.checkLight();

                        //if that isn't enough, we throw our hands up in despair and then die
                        checkState(chunk.isLightPopulated(), "chunk at (%d, %d) couldn't be lit!", x, z);
                    }
                }

                if (!this.world.provider.hasSkyLight()) {
                    //vanilla's lighting code is completely clueless in dimensions without skylight: checkLight() above only sets the isLightPopulated flag and
                    //  leaves the block light at zero, and a chunk which was loaded from disk is never relit again. since fp2 generates chunks which are far
                    //  away from any player (and therefore never ticked by the vanilla light engine), the chunk has to be lit manually - otherwise it will be
                    //  rendered with no light at all (the nether's lava oceans are the most obvious example)
                    int height = this.world.provider.getActualHeight();
                    if (ChunkBlockLightFixer1_12.needsRelight(chunk, height)) {
                        ChunkBlockLightFixer1_12.relight(this.world, chunk, height);
                    }
                }

                this.io.saveChunk(this.world, chunk);
            }).join();
        }
    }
}
