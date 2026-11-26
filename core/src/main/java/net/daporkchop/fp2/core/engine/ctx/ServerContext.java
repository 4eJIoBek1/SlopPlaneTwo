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

package net.daporkchop.fp2.core.engine.ctx;

import lombok.Getter;
import lombok.NonNull;
import net.daporkchop.fp2.core.config.FP2Config;
import net.daporkchop.fp2.core.engine.DirectTilePosAccess;
import net.daporkchop.fp2.core.engine.TilePos;
import net.daporkchop.fp2.core.engine.api.ctx.IFarServerContext;
import net.daporkchop.fp2.core.engine.api.server.IFarTileProvider;
import net.daporkchop.fp2.core.engine.server.tracking.Tracker;
import net.daporkchop.fp2.core.engine.tile.TileSnapshot;
import net.daporkchop.fp2.core.network.packet.debug.server.SPacketDebugUpdateStatistics;
import net.daporkchop.fp2.core.network.packet.standard.server.SPacketTileData;
import net.daporkchop.fp2.core.network.packet.standard.server.SPacketUnloadTiles;
import net.daporkchop.fp2.core.server.player.IFarPlayerServer;
import net.daporkchop.fp2.core.server.world.level.IFarLevelServer;
import net.daporkchop.fp2.core.util.annotation.CalledFromServerThread;
import net.daporkchop.lib.common.annotation.TransferOwnership;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static net.daporkchop.fp2.core.debug.FP2Debug.*;
import static net.daporkchop.lib.common.util.PValidation.*;

/**
 * @author DaPorkchop_
 */
@Getter
public class ServerContext implements IFarServerContext {
    protected static final long MAX_UPDATE_BATCH_BYTES = 256L * 1024L;
    protected static final long MAX_CONNECTED_L0_BATCH_BYTES = 1024L * 1024L;
    protected static final long MAX_L0_HOLD_TICKS = 12L;

    static final class PendingUpdate {
        TileSnapshot snapshot;
        final long firstQueuedTick;

        PendingUpdate(@NonNull TileSnapshot snapshot, long firstQueuedTick) {
            this.snapshot = snapshot;
            this.firstQueuedTick = firstQueuedTick;
        }
    }

    protected final IFarPlayerServer player;
    protected final IFarLevelServer world;
    protected final IFarTileProvider tileProvider;

    protected final Tracker tracker;

    protected Set<TilePos> unloadTilesQueue = DirectTilePosAccess.newPositionHashSet();
    protected final Map<TilePos, TileSnapshot> sendTilesQueue = DirectTilePosAccess.newPositionKeyedLinkedHashMap();
    protected final Map<TilePos, PendingUpdate> pendingUpdateTiles = DirectTilePosAccess.newPositionKeyedLinkedHashMap();

    protected FP2Config config;

    protected boolean closed = false;

    private int debugLastUpdateSent;

    public ServerContext(@NonNull IFarPlayerServer player, @NonNull IFarLevelServer world, @NonNull FP2Config config) {
        this.player = player;
        this.world = world;
        this.config = config;

        this.tileProvider = world.tileProvider();
        this.tracker = this.tileProvider.trackerManager().beginTracking(this);
    }

    @CalledFromServerThread
    @Override
    public void notifyConfigChange(@NonNull FP2Config config) {
        checkState(!this.closed, "already closed!");
        this.config = config;

        //no reason to bother scheduling an update immediately, it'll happen on the next server tick anyway
    }

    @CalledFromServerThread
    @Override
    public void update() {
        checkState(!this.closed, "already closed!");

        this.tracker.update();

        this.flushSendQueue();
        this.debugUpdate();
    }

    protected void flushSendQueue() {
        //check if the send queue contains any items to see if any additional work is necessary.
        // this is safe to call without synchronization: it only accesses the map size, which involves reading a single int field. the worst that can happen is we miss a
        // queued tile for one tick.
        if (this.unloadTilesQueue.isEmpty() && this.sendTilesQueue.isEmpty() && this.pendingUpdateTiles.isEmpty()) {
            //the send queue is empty, nothing to do!
            return;
        }

        Set<TilePos> unloadedTiles = null;
        int loadedTilesCount;

        synchronized (this) {
            if (!this.unloadTilesQueue.isEmpty()) {
                unloadedTiles = this.unloadTilesQueue;
                this.unloadTilesQueue = DirectTilePosAccess.newPositionHashSet(); //replace with a new set
            }

            loadedTilesCount = this.trySendPendingUpdates() + this.trySendTiles();
        }

        if (loadedTilesCount != 0) {
            this.tracker.notifyTilesSent();
        }

        //send packet for unloaded tiles
        if (unloadedTiles != null) {
            this.player.fp2_IFarPlayer_sendPacket(SPacketUnloadTiles.create(unloadedTiles));
        }
    }

    private void debugUpdate() {
        if (!FP2_DEBUG) { //debug mode not enabled, do nothing
            return;
        }

        if (++this.debugLastUpdateSent == 20) { //send a debug statistics update packet once every 20s
            this.debugLastUpdateSent = 0;

            this.player.fp2_IFarPlayer_sendPacket(SPacketDebugUpdateStatistics.create(this.tracker.debugStats()));
        }
    }

    @CalledFromServerThread
    @Override
    public void close() {
        checkState(!this.closed, "already closed!");
        this.closed = true;

        this.tracker.close();
    }

    public int queuedTilesToSend() {
        return this.sendTilesQueue.size() >> 4; //divide by 16 so that we're still prefetching quite a few tiles even if things seem to be full
    }

    @Override
    public void sendTile(@TransferOwnership @NonNull TileSnapshot snapshot) {
        if (this.closed) { //this context has been closed - silently discard all tile data
            return;
        }

        synchronized (this) {
            this.unloadTilesQueue.remove(snapshot.pos());
            this.pendingUpdateTiles.remove(snapshot.pos());
            if (this.sendTilesQueue.isEmpty() && this.player.fp2_IFarPlayer_flowControl().mayWrite(snapshot.dataSize())) {
                this.player.fp2_IFarPlayer_sendPacket(SPacketTileData.create(Collections.singletonList(snapshot)), this.sentHandler());
            } else {
                this.sendTilesQueue.put(snapshot.pos(), snapshot);
            }
        }
    }

    @Override
    public void sendTileUpdate(@TransferOwnership @NonNull TileSnapshot snapshot) {
        if (this.closed) { //this context has been closed - silently discard all tile data
            return;
        }

        synchronized (this) {
            this.unloadTilesQueue.remove(snapshot.pos());
            this.sendTilesQueue.remove(snapshot.pos());
            long now = this.world.timestamp();
            PendingUpdate existing = this.pendingUpdateTiles.get(snapshot.pos());
            if (existing != null) {
                existing.snapshot = snapshot;
            } else {
                this.pendingUpdateTiles.put(snapshot.pos(), new PendingUpdate(snapshot, now));
            }
        }
    }

    /**
     * Workaround: when sending packets, the packet sent handler may be invoked synchronously. As the packet send handler calls {@link #trySendTiles()}, which can send more packets using
     * the same send handler, {@link #trySendTiles()} can end up calling itself recursively. To avoid this, we simply set this to {@code true} when {@link #trySendTiles()} and reset it
     * on exit, ensuring that we aren't iterating over the send tiles queue twice at the same time.
     */
    private boolean sendingTiles = false;

    private Consumer<Throwable> sentHandler() {
        return cause -> {
            if (cause == null) {
                this.tracker.notifyTilesSent();

                try {
                    this.trySendPendingUpdates();
                    this.trySendTiles();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        };
    }

    private static boolean hasPending26Neighbor(@NonNull TilePos pos, @NonNull Predicate<TilePos> pred) {
        int px = pos.x();
        int py = pos.y();
        int pz = pos.z();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (pred.test(new TilePos(0, px + dx, py + dy, pz + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static List<List<TileSnapshot>> selectReadyL0Components(
            @NonNull Map<TilePos, PendingUpdate> pendingL0,
            long currentTick,
            boolean deferAllL0,
            @NonNull Predicate<TilePos> isUpdatePending) {
        if (pendingL0.isEmpty() || deferAllL0) {
            return Collections.emptyList();
        }

        Set<TilePos> visited = DirectTilePosAccess.newPositionHashSet();
        ArrayDeque<TilePos> queue = new ArrayDeque<>();
        List<List<TileSnapshot>> readyComponents = new ArrayList<>();

        for (TilePos root : pendingL0.keySet()) {
            if (!visited.add(root)) {
                continue;
            }
            List<TilePos> compPos = new ArrayList<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                TilePos cur = queue.poll();
                compPos.add(cur);
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
                            if (pendingL0.containsKey(n) && visited.add(n)) {
                                queue.add(n);
                            }
                        }
                    }
                }
            }

            long minFirstQueuedTick = Long.MAX_VALUE;
            boolean blocked = false;
            for (int i = 0, ni = compPos.size(); i < ni; i++) {
                TilePos pos = compPos.get(i);
                PendingUpdate pu = pendingL0.get(pos);
                if (pu.firstQueuedTick < minFirstQueuedTick) {
                    minFirstQueuedTick = pu.firstQueuedTick;
                }
                if (!blocked && (pu.snapshot.timestamp() >= currentTick
                        || isUpdatePending.test(pos)
                        || hasPending26Neighbor(pos, isUpdatePending))) {
                    blocked = true;
                }
            }

            if (!blocked || (currentTick - minFirstQueuedTick) >= MAX_L0_HOLD_TICKS) {
                List<TileSnapshot> snaps = new ArrayList<>(compPos.size());
                for (int i = 0, ni = compPos.size(); i < ni; i++) {
                    snaps.add(pendingL0.get(compPos.get(i)).snapshot);
                }
                readyComponents.add(snaps);
            }
        }

        return readyComponents;
    }

    private synchronized int trySendPendingUpdates() {
        if (this.sendingTiles || this.pendingUpdateTiles.isEmpty()) {
            return 0;
        }

        long currentTick = this.world.timestamp();
        Map<TilePos, PendingUpdate> pendingL0 = null;
        List<TileSnapshot> readyCoarse = null;
        for (Map.Entry<TilePos, PendingUpdate> entry : this.pendingUpdateTiles.entrySet()) {
            TilePos pos = entry.getKey();
            PendingUpdate pu = entry.getValue();
            if (pos.level() == 0) {
                if (pendingL0 == null) {
                    pendingL0 = DirectTilePosAccess.newPositionKeyedLinkedHashMap();
                }
                pendingL0.put(pos, pu);
            } else if (pu.snapshot.timestamp() < currentTick) {
                if (readyCoarse == null) {
                    readyCoarse = new ArrayList<>();
                }
                readyCoarse.add(pu.snapshot);
            }
        }

        List<List<TileSnapshot>> l0Components = pendingL0 == null
                ? Collections.emptyList()
                : selectReadyL0Components(
                        pendingL0,
                        currentTick,
                        this.tileProvider.hasPendingDirtyUpdates(),
                        this.tileProvider.trackerManager()::isUpdatePending);

        if (l0Components.isEmpty() && readyCoarse == null) {
            return 0;
        }

        if (readyCoarse != null && readyCoarse.size() > 1) {
            readyCoarse.sort(Comparator.comparingInt(s -> s.pos().level()));
        }

        int sentTiles = 0;
        this.sendingTiles = true;
        try {
            List<TileSnapshot> batch = new ArrayList<>();
            long batchBytes = 0L;

            L0_LOOP:
            for (int c = 0, nc = l0Components.size(); c < nc; c++) {
                List<TileSnapshot> comp = l0Components.get(c);
                long compBytes = 0L;
                for (int i = 0, ni = comp.size(); i < ni; i++) {
                    compBytes += comp.get(i).dataSize();
                }

                if (!batch.isEmpty() && (batchBytes + compBytes > MAX_UPDATE_BATCH_BYTES
                        || !this.player.fp2_IFarPlayer_flowControl().mayWrite(batchBytes + compBytes))) {
                    this.player.fp2_IFarPlayer_sendPacket(SPacketTileData.create(batch), this.sentHandler());
                    batch = new ArrayList<>();
                    batchBytes = 0L;
                }

                if (compBytes <= MAX_CONNECTED_L0_BATCH_BYTES) {
                    if (batch.isEmpty() && !this.player.fp2_IFarPlayer_flowControl().mayWrite(compBytes)) {
                        break;
                    }
                    for (int i = 0, ni = comp.size(); i < ni; i++) {
                        TileSnapshot tile = comp.get(i);
                        this.pendingUpdateTiles.remove(tile.pos());
                        batch.add(tile);
                    }
                    batchBytes += compBytes;
                    sentTiles += comp.size();
                } else {
                    for (int i = 0, ni = comp.size(); i < ni; i++) {
                        TileSnapshot tile = comp.get(i);
                        long tileBytes = tile.dataSize();
                        if (!batch.isEmpty() && (batchBytes + tileBytes > MAX_UPDATE_BATCH_BYTES
                                || !this.player.fp2_IFarPlayer_flowControl().mayWrite(batchBytes + tileBytes))) {
                            this.player.fp2_IFarPlayer_sendPacket(SPacketTileData.create(batch), this.sentHandler());
                            batch = new ArrayList<>();
                            batchBytes = 0L;
                        }
                        if (batch.isEmpty() && !this.player.fp2_IFarPlayer_flowControl().mayWrite(tileBytes)) {
                            break L0_LOOP;
                        }
                        this.pendingUpdateTiles.remove(tile.pos());
                        batch.add(tile);
                        batchBytes += tileBytes;
                        sentTiles++;
                    }
                }
            }

            if (!batch.isEmpty()) {
                this.player.fp2_IFarPlayer_sendPacket(SPacketTileData.create(batch), this.sentHandler());
                batch = new ArrayList<>();
                batchBytes = 0L;
            }

            if (readyCoarse != null) {
                for (int i = 0, n = readyCoarse.size(); i < n; i++) {
                    TileSnapshot tile = readyCoarse.get(i);
                    long tileBytes = tile.dataSize();

                    if (!batch.isEmpty() && (batchBytes + tileBytes > MAX_UPDATE_BATCH_BYTES
                            || !this.player.fp2_IFarPlayer_flowControl().mayWrite(batchBytes + tileBytes))) {
                        this.player.fp2_IFarPlayer_sendPacket(SPacketTileData.create(batch), this.sentHandler());
                        batch = new ArrayList<>();
                        batchBytes = 0L;
                    }

                    if (batch.isEmpty() && !this.player.fp2_IFarPlayer_flowControl().mayWrite(tileBytes)) {
                        break;
                    }

                    this.pendingUpdateTiles.remove(tile.pos());
                    batch.add(tile);
                    batchBytes += tileBytes;
                    sentTiles++;
                }

                if (!batch.isEmpty()) {
                    this.player.fp2_IFarPlayer_sendPacket(SPacketTileData.create(batch), this.sentHandler());
                }
            }

            return sentTiles;
        } finally {
            this.sendingTiles = false;
        }
    }

    private synchronized int trySendTiles() {
        if (this.sendingTiles || this.sendTilesQueue.isEmpty()) {
            return 0;
        }

        try {
            int sentTiles = 0;
            this.sendingTiles = true;

            Iterator<TileSnapshot> itr = this.sendTilesQueue.values().iterator();
            do {
                TileSnapshot next = itr.next();
                if (!this.player.fp2_IFarPlayer_flowControl().mayWrite(next.dataSize())) {
                    break;
                }
                itr.remove();

                this.player.fp2_IFarPlayer_sendPacket(SPacketTileData.create(Collections.singletonList(next)), this.sentHandler());

                sentTiles++;
            } while (itr.hasNext());

            return sentTiles;
        } finally {
            this.sendingTiles = false;
        }
    }

    @Override
    public void sendTileUnload(@NonNull TilePos pos) {
        if (this.closed) { //this context has been closed - silently discard all tile data
            return;
        }

        synchronized (this) {
            if (this.unloadTilesQueue.add(pos)) {
                this.sendTilesQueue.remove(pos);
                this.pendingUpdateTiles.remove(pos);
            }
        }
    }
}
