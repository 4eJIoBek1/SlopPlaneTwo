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

package net.daporkchop.fp2.core.engine.client;

import lombok.NonNull;
import lombok.val;
import net.daporkchop.fp2.core.engine.DirectTilePosAccess;
import net.daporkchop.fp2.core.engine.Tile;
import net.daporkchop.fp2.core.engine.TileCoordLimits;
import net.daporkchop.fp2.core.engine.TilePos;
import net.daporkchop.fp2.core.engine.client.bake.BakeOutput;
import net.daporkchop.fp2.core.engine.client.bake.IRenderBaker;
import net.daporkchop.fp2.core.engine.client.bake.storage.BakeStorage;
import net.daporkchop.fp2.core.engine.client.index.RenderIndex;
import net.daporkchop.fp2.core.engine.tile.ITileSnapshot;
import net.daporkchop.fp2.core.util.listener.ListenerList;
import net.daporkchop.fp2.core.util.threading.BlockingSupport;
import net.daporkchop.fp2.core.util.threading.scheduler.NoFutureScheduler;
import net.daporkchop.fp2.core.util.threading.scheduler.Scheduler;
import net.daporkchop.fp2.gl.attribute.AttributeStruct;
import net.daporkchop.fp2.gl.buffer.upload.BufferUploader;
import net.daporkchop.lib.common.closeable.PResourceUtil;
import net.daporkchop.lib.common.misc.release.AbstractReleasable;
import net.daporkchop.lib.common.misc.threadfactory.PThreadFactories;
import net.daporkchop.lib.common.pool.recycler.Recycler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static net.daporkchop.fp2.core.FP2Core.*;
import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * @author DaPorkchop_
 */
public final class BakeManager<VertexType extends AttributeStruct> extends AbstractReleasable implements FarTileCache.Listener, Consumer<TilePos> {
    static final int MAX_L0_DEFER_FRAMES = 6;

    static final class PendingBake<VertexType extends AttributeStruct> implements AutoCloseable {
        final BakeOutput<VertexType> output;
        final boolean present;
        final int gen;
        int deferFrames;

        PendingBake(BakeOutput<VertexType> output, boolean present, int gen) {
            this.output = output;
            this.present = present;
            this.gen = gen;
        }

        long sizeBytes() {
            return this.output != null ? this.output.sizeBytes() : 0L;
        }

        @Override
        public void close() {
            if (this.output != null) {
                this.output.close();
            }
        }
    }

    private final AbstractFarRenderer<VertexType> renderer;

    private final FarTileCache tileCache;
    private final IRenderBaker<VertexType> baker;

    private final Scheduler<TilePos, Void> bakeScheduler;
    private final TileCoordLimits coordLimits;

    private final ListenerList<FarTileCache.Listener>.Handle tileCacheListenerHandle;

    private final Map<TilePos, PendingBake<VertexType>> pendingDataUpdates = DirectTilePosAccess.newPositionKeyedConcurrentHashMap();
    private final ConcurrentHashMap<TilePos, Integer> pendingL0BakeGen = new ConcurrentHashMap<>();
    private final Set<TilePos> uploadedTiles = DirectTilePosAccess.newPositionHashSet();
    private final Semaphore dataUpdatesLock = new Semaphore(fp2().globalConfig().performance().maxBakesProcessedPerFrame());

    public BakeManager(@NonNull AbstractFarRenderer<VertexType> renderer, @NonNull FarTileCache tileCache, @NonNull IRenderBaker<VertexType> baker) {
        this.renderer = renderer;

        this.tileCache = tileCache;
        this.baker = baker;

        this.coordLimits = new TileCoordLimits(renderer.context().level().coordLimits());

        this.bakeScheduler = new NoFutureScheduler<>(this, this.renderer.context().level().workerManager().createChildWorkerGroup()
                .threads(fp2().globalConfig().performance().bakeThreads())
                .threadFactory(PThreadFactories.builder().daemon().minPriority().collapsingId().name("FP2 Rendering Thread #%d").build()));

        this.tileCacheListenerHandle = this.tileCache.addListener(this);
        this.tilesChanged(this.tileCache.getAllPositions());
    }

    @Override
    protected void doRelease() {
        //TODO: use try-with-resources here?
        this.tileCacheListenerHandle.close();

        //reset permit count to maximum possible to prevent infinite blocking while shutting down executor
        this.dataUpdatesLock.drainPermits();
        this.dataUpdatesLock.release(Integer.MAX_VALUE);

        //this will block until all of the bake threads have exited
        this.bakeScheduler.close();
        this.pendingL0BakeGen.clear();

        //release all of the bake outputs and remove them from the map
        for (Iterator<TilePos> itr = this.pendingDataUpdates.keySet().iterator(); itr.hasNext(); ) {
            this.pendingDataUpdates.computeIfPresent(itr.next(), (pos, pending) -> {
                pending.close();
                return null;
            });
        }
    }

    private void scheduleOutputs(@NonNull Collection<TilePos> outputs) {
        if (outputs.isEmpty()) {
            return;
        }
        //mark ALL L0 outputs as in-flight before scheduling any of them so concurrent bake/tick threads see siblings
        for (TilePos outputPos : outputs) {
            if (outputPos.level() == 0) {
                this.pendingL0BakeGen.merge(outputPos, 1, Integer::sum);
            }
        }
        outputs.forEach(this.bakeScheduler::schedule);
    }

    @Override
    public void tileChanged(@NonNull TilePos pos) {
        Set<TilePos> outputs = DirectTilePosAccess.newPositionHashSet();
        this.baker.bakeOutputs(pos).forEach(outputPos -> {
            if (outputPos.isLevelValid()) {
                outputs.add(outputPos);
            }
        });
        this.scheduleOutputs(outputs);
    }

    @Override
    public void tilesChanged(@NonNull Set<TilePos> positions) {
        if (positions.isEmpty()) {
            return;
        }
        Set<TilePos> outputs = DirectTilePosAccess.newPositionHashSet();
        for (TilePos pos : positions) {
            this.baker.bakeOutputs(pos).forEach(outputPos -> {
                if (outputPos.isLevelValid()) {
                    outputs.add(outputPos);
                }
            });
        }
        this.scheduleOutputs(outputs);
    }

    /**
     * Bakes the tile at the given position.
     *
     * @deprecated internal API, do not touch!
     */
    @Override
    @Deprecated
    public void accept(@NonNull TilePos pos) { //this function is called from inside of bakeScheduler, which doesn't execute the task multiple times on the same position
        final int bakeGen = pos.level() == 0 ? this.pendingL0BakeGen.getOrDefault(pos, 0) : 0;
        boolean updatedData = false;
        try {
            //get the list of input tile positions
            List<TilePos> inputTilePositions = this.baker.bakeInputs(pos);

            //double-check that the original (provoking) tile position is one of the inputs
            int provokingTilePositionInputIndex = inputTilePositions.indexOf(pos);
            checkState(provokingTilePositionInputIndex >= 0, "bake inputs for %s don't include the tile itself: %s", pos, inputTilePositions);

            //get the input tile data from the tile cache
            ITileSnapshot[] compressedInputTiles = this.tileCache.getTilesCached(inputTilePositions)
                    .toArray(new ITileSnapshot[inputTilePositions.size()]);

            //fast path: if the provoking tile was unloaded or is empty, we can skip baking it entirely
            ITileSnapshot provoking = compressedInputTiles[provokingTilePositionInputIndex];
            if (provoking == null) { //tile isn't cached any more
                this.updateData(pos, new PendingBake<>(null, false, bakeGen));
                updatedData = true;
                return;
            } else if (provoking.isEmpty()) { //tile data is empty
                this.updateData(pos, new PendingBake<>(null, true, bakeGen));
                updatedData = true;
                return;
            }

            Recycler<Tile> recycler = Tile.recycler();
            Tile[] srcs = new Tile[compressedInputTiles.length];
            try {
                for (int i = 0; i < srcs.length; i++) { //inflate tiles
                    if (compressedInputTiles[i] != null) {
                        srcs[i] = compressedInputTiles[i].loadTile(recycler, Tile.CODEC);
                    }
                }

                BakeOutput<VertexType> output = new BakeOutput<>(this.renderer.vertexFormat, this.renderer.indexFormat, this.renderer.alloc);
                try {
                    this.baker.bake(pos, srcs, output);

                    if (output.isEmpty()) {
                        output.close();
                        output = null;
                    }

                    this.updateData(pos, new PendingBake<>(output, true, bakeGen));
                    updatedData = true;
                } catch (Throwable t) {
                    if (output != null) {
                        output.close();
                    }
                    throw t;
                }
            } finally { //release tiles again
                for (Tile src : srcs) {
                    if (src != null) {
                        recycler.release(src);
                    }
                }
            }

            this.tileCache.tryCompressExistingTile(pos);
        } catch (Throwable t) {
            if (!updatedData && pos.level() == 0 && bakeGen != 0) {
                this.pendingL0BakeGen.remove(pos, bakeGen);
            }
            throw t;
        }
    }

    private void updateData(@NonNull TilePos pos, @NonNull PendingBake<VertexType> pendingBake) throws InterruptedException {
        BlockingSupport.managedAcquire(this.dataUpdatesLock, 1);

        this.pendingDataUpdates.merge(pos, pendingBake, (oldBake, newBake) -> {
            //release old bake output to avoid potential memory leak when silently replacing entries
            oldBake.close();
            return newBake;
        });
    }

    static <V extends AttributeStruct> List<List<TilePos>> selectReadyL0BakeComponents(
            @NonNull Map<TilePos, PendingBake<V>> l0Snap,
            @NonNull Map<TilePos, Integer> pendingL0BakeGen,
            boolean allowDefer) {
        if (l0Snap.isEmpty()) {
            return Collections.emptyList();
        }

        Set<TilePos> deferred = DirectTilePosAccess.newPositionHashSet();
        ArrayDeque<TilePos> queue = new ArrayDeque<>();

        if (allowDefer) {
            for (Map.Entry<TilePos, PendingBake<V>> entry : l0Snap.entrySet()) {
                TilePos pos = entry.getKey();
                PendingBake<V> pb = entry.getValue();
                if (pb.deferFrames >= MAX_L0_DEFER_FRAMES) {
                    continue;
                }
                int curGen = pendingL0BakeGen.getOrDefault(pos, 0);
                boolean block = curGen > pb.gen;
                if (!block) {
                    int px = pos.x();
                    int py = pos.y();
                    int pz = pos.z();
                    NEIGHBOR_CHECK:
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) {
                                    continue;
                                }
                                TilePos n = new TilePos(0, px + dx, py + dy, pz + dz);
                                Integer nGen = pendingL0BakeGen.get(n);
                                if (nGen != null) {
                                    PendingBake<V> npb = l0Snap.get(n);
                                    if (npb == null || npb.gen < nGen) {
                                        block = true;
                                        break NEIGHBOR_CHECK;
                                    }
                                }
                            }
                        }
                    }
                }
                if (block) {
                    deferred.add(pos);
                    queue.add(pos);
                }
            }

            while (!queue.isEmpty()) {
                TilePos cur = queue.poll();
                int cx = cur.x();
                int cy = cur.y();
                int cz = cur.z();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }
                            TilePos n = new TilePos(0, cx + dx, cy + dy, cz + dz);
                            PendingBake<V> npb = l0Snap.get(n);
                            if (npb != null && npb.deferFrames < MAX_L0_DEFER_FRAMES && deferred.add(n)) {
                                queue.add(n);
                            }
                        }
                    }
                }
            }

            for (TilePos pos : deferred) {
                l0Snap.get(pos).deferFrames++;
            }
        }

        Set<TilePos> visited = DirectTilePosAccess.newPositionHashSet();
        List<List<TilePos>> components = new ArrayList<>();
        for (TilePos root : l0Snap.keySet()) {
            if (deferred.contains(root) || !visited.add(root)) {
                continue;
            }
            List<TilePos> comp = new ArrayList<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                TilePos cur = queue.poll();
                comp.add(cur);
                int cx = cur.x();
                int cy = cur.y();
                int cz = cur.z();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }
                            TilePos n = new TilePos(0, cx + dx, cy + dy, cz + dz);
                            if (l0Snap.containsKey(n) && !deferred.contains(n) && visited.add(n)) {
                                queue.add(n);
                            }
                        }
                    }
                }
            }
            components.add(comp);
        }

        return components;
    }

    /**
     * Processes tile update events from completed tile bake operations, forwarding them to the bake storage and render index.
     *
     * @param bufferUploader the {@link BufferUploader} to use for uploading bake output data
     * @param bakeStorage    the {@link BakeStorage} to store uploaded tile data in
     * @param renderIndex    the {@link RenderIndex} to notify of changed tiles
     */
    public void tick(@NonNull BufferUploader bufferUploader, @NonNull BakeStorage<VertexType> bakeStorage, @NonNull RenderIndex<VertexType> renderIndex) {
        final int dataUpdatesCount = this.pendingDataUpdates.size();
        if (dataUpdatesCount != 0) {
            final long availableBufferUploaderCapacity = bufferUploader.estimateCapacity();
            final long maximumBufferUploaderCapacity = bufferUploader.maximumCapacity();
            final int maxBakes = fp2().globalConfig().performance().maxBakesProcessedPerFrame();
            final int bakeThreads = fp2().globalConfig().performance().bakeThreads();
            final boolean allowL0Defer = dataUpdatesCount < Math.max(maxBakes - (bakeThreads << 1), 0);

            Map<TilePos, PendingBake<VertexType>> l0Snap = null;
            List<TilePos> coarsePositions = null;
            for (Map.Entry<TilePos, PendingBake<VertexType>> entry : this.pendingDataUpdates.entrySet()) {
                TilePos pos = entry.getKey();
                PendingBake<VertexType> pb = entry.getValue();
                if (pb == null) {
                    continue;
                }
                if (pos.level() == 0) {
                    if (l0Snap == null) {
                        l0Snap = DirectTilePosAccess.newPositionKeyedHashMap();
                    }
                    l0Snap.put(pos, pb);
                } else {
                    if (coarsePositions == null) {
                        coarsePositions = new ArrayList<>();
                    }
                    coarsePositions.add(pos);
                }
            }

            List<List<TilePos>> readyL0Components = l0Snap == null
                    ? Collections.emptyList()
                    : selectReadyL0BakeComponents(l0Snap, this.pendingL0BakeGen, allowL0Defer);

            //pre-allocate a bit of extra space in case it grows while we're iterating
            Map<TilePos, BakeOutput<VertexType>> dataUpdates = DirectTilePosAccess.newPositionKeyedHashMap(dataUpdatesCount + (dataUpdatesCount >> 3));
            List<TilePos> poppedPositions = new ArrayList<>(dataUpdatesCount);
            List<Boolean> poppedPresent = new ArrayList<>(dataUpdatesCount);

            //the total size (in bytes) of the data which is queued to be sent to the GPU
            long dataUploadSize = 0L;
            try {
                for (int c = 0, nc = readyL0Components.size(); c < nc; c++) {
                    List<TilePos> comp = readyL0Components.get(c);
                    long compBytes = 0L;
                    for (int i = 0, ni = comp.size(); i < ni; i++) {
                        compBytes += l0Snap.get(comp.get(i)).sizeBytes();
                    }

                    if (!dataUpdates.isEmpty()
                            && compBytes > availableBufferUploaderCapacity - dataUploadSize
                            && compBytes <= maximumBufferUploaderCapacity) {
                        break;
                    }

                    for (int i = 0, ni = comp.size(); i < ni; i++) {
                        TilePos pos = comp.get(i);
                        PendingBake<VertexType> pending;
                        do {
                            pending = this.pendingDataUpdates.get(pos);
                            if (pending == null) {
                                break;
                            }
                        } while (!this.pendingDataUpdates.remove(pos, pending));

                        if (pending != null) {
                            if (pending.gen != 0) {
                                this.pendingL0BakeGen.remove(pos, pending.gen);
                            }
                            dataUploadSize += pending.sizeBytes();
                            dataUpdates.put(pos, pending.output);
                            poppedPositions.add(pos);
                            poppedPresent.add(pending.present);
                        }
                    }
                }

                if (coarsePositions != null) {
                    COARSE_LOOP:
                    for (int i = 0, n = coarsePositions.size(); i < n; i++) {
                        TilePos pos = coarsePositions.get(i);
                        PendingBake<VertexType> pending;
                        do {
                            pending = this.pendingDataUpdates.get(pos);
                            if (pending == null) {
                                continue COARSE_LOOP;
                            }
                            if (pending.output != null) {
                                long dataSize = pending.output.sizeBytes();

                                if (dataSize > maximumBufferUploaderCapacity) {
                                    //this tile will always exceed the buffer uploader's non-blocking capacity, so we may as well upload it as soon as possible
                                    //  as the GPU will end up getting stalled no matter what.
                                    //that said, this should never actually occur, and if it ever does it's most likely a bug.
                                } else if (dataSize > availableBufferUploaderCapacity - dataUploadSize) {
                                    //avoid uploading more data than the buffer uploader has available space to avoid stalling the GPU pipeline. we'll simply
                                    //  skip all remaining data updates and defer them until the buffer uploader has more space available (which will hopefully
                                    //  occur within the next few frames)
                                    break COARSE_LOOP;
                                }
                            }
                        } while (!this.pendingDataUpdates.remove(pos, pending));

                        dataUploadSize += pending.sizeBytes();
                        dataUpdates.put(pos, pending.output);
                        poppedPositions.add(pos);
                        poppedPresent.add(pending.present);
                    }
                }

                if (!dataUpdates.isEmpty()) {
                    //execute the bulk tile data update
                    bakeStorage.update(dataUpdates);
                    renderIndex.notifyTilesChanged(dataUpdates.keySet());
                    bufferUploader.flush();
                }

                if (!poppedPositions.isEmpty()) {
                    //update renderable/hidden state only after the baked meshes are actually uploaded, preventing coarse parents from hiding prematurely
                    Set<TilePos> renderabilityDirty = null;
                    for (int i = 0, n = poppedPositions.size(); i < n; i++) {
                        TilePos pos = poppedPositions.get(i);
                        boolean nowPresent = poppedPresent.get(i) && this.tileCache.getTileCached(pos) != null;
                        boolean changed = nowPresent ? this.uploadedTiles.add(pos) : this.uploadedTiles.remove(pos);
                        if (changed) {
                            if (renderabilityDirty == null) {
                                renderabilityDirty = DirectTilePosAccess.newPositionHashSet();
                            }
                            renderabilityDirty.add(pos);
                            TilePos parentBase = pos.up();
                            if (parentBase.isLevelValid()) {
                                parentBase.allPositionsInBB(1, 1).forEach(renderabilityDirty::add);
                            }
                        }
                    }
                    if (renderabilityDirty != null) {
                        val hidden = DirectTilePosAccess.newPositionHashSet();
                        val shown = DirectTilePosAccess.newPositionHashSet();
                        for (TilePos pos : renderabilityDirty) {
                            if (!pos.isLevelValid() || !this.coordLimits.contains(pos)) {
                                continue;
                            }
                            boolean renderable = pos.level() == 0
                                    || !this.uploadedTiles.contains(pos)
                                    || pos.down().allPositionsInBB(1, 3).anyMatch(child -> this.coordLimits.contains(child) && !this.uploadedTiles.contains(child));
                            (renderable ? shown : hidden).add(pos);
                        }
                        renderIndex.updateHidden(hidden, shown);
                    }
                }
            } finally {
                //release all bake outputs (which are still sitting around in memory, and we now own the handles to)
                PResourceUtil.closeAll(dataUpdates.values());
            }

            //this is the best we can do of resetting a semaphore to its initial permit count
            this.dataUpdatesLock.drainPermits();
            this.dataUpdatesLock.release(Math.max(maxBakes - this.pendingDataUpdates.size(), 0));
        }
    }
}
