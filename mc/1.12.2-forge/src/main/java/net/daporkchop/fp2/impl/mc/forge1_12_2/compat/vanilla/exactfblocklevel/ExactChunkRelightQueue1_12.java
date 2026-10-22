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

import lombok.NonNull;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Queues chunks for block light computation by the vanilla light engine.
 * <p>
 * {@link Chunk#checkLight()} only handles <b>skylight</b>: in dimensions without sky light (the nether and the end) it does nothing but set the
 * {@code isLightPopulated} flag, leaving the block light at zero. Since fp2 generates and saves chunks which are far away from any player, the chunk
 * would be written to disk with no block light at all - the vanilla light engine only ever revisits chunks which are being ticked by a player, and a
 * chunk which was loaded from disk with {@code isLightPopulated = true} and a full relight counter is never lit again. The far terrain would then
 * render those chunks completely dark (most noticeably the lava oceans in the nether).
 * <p>
 * The light engine is not thread-safe, so the light computation has to happen on the server thread. In order not to cause a noticeable stall, chunks are
 * lit incrementally over the course of multiple ticks, exactly like vanilla does it for chunks which become visible to a player. Once the light has been
 * computed, the chunk is saved immediately, which causes fp2 to rebuild the affected tiles with proper lighting.
 *
 * @author DaPorkchop_
 */
public final class ExactChunkRelightQueue1_12 {
    /**
     * The number of {@link Chunk#enqueueRelightChecks()} calls needed to process all 4096 cell columns of a chunk.
     */
    private static final int CALLS_PER_CHUNK = 4096 / 8;
    /**
     * The maximum number of {@link Chunk#enqueueRelightChecks()} calls to perform per server tick, across all queued chunks. Each call processes 8 cell
     * columns (128 blocks), so this is small enough to not be noticeable while still lighting a chunk within a matter of seconds.
     */
    private static final int CALLS_PER_TICK = 32;

    private static final Map<WorldServer, ArrayDeque<Pending>> QUEUES = new IdentityHashMap<>();

    public static void enqueue(@NonNull WorldServer world, int x, int z) {
        ArrayDeque<Pending> queue;
        synchronized (QUEUES) {
            queue = QUEUES.get(world);
            if (queue == null) {
                QUEUES.put(world, queue = new ArrayDeque<>());
            }
        }

        synchronized (queue) {
            queue.addLast(new Pending(x, z));
        }
    }

    public static void tick(@NonNull WorldServer world) {
        ArrayDeque<Pending> queue;
        synchronized (QUEUES) {
            queue = QUEUES.get(world);
        }
        if (queue == null) {
            return;
        }

        int budget = CALLS_PER_TICK;
        while (budget > 0) {
            Pending pending;
            synchronized (queue) {
                pending = queue.peekFirst();
            }
            if (pending == null) {
                break;
            }

            Chunk chunk = world.getChunkProvider().getLoadedChunk(pending.x, pending.z);
            if (chunk == null) {
                //the chunk was unloaded again before it could be lit - there's nothing we can do anymore, as vanilla won't revisit it either
                synchronized (queue) {
                    queue.pollFirst();
                }
                continue;
            }

            if (pending.calls == 0) {
                //start from the bottom of the chunk (this is where the lava is in the nether)
                chunk.resetRelightChecks();
            }

            int done = Math.min(budget, CALLS_PER_CHUNK - pending.calls);
            for (int i = 0; i < done; i++) {
                chunk.enqueueRelightChecks();
            }
            pending.calls += done;
            budget -= done;

            if (pending.calls >= CALLS_PER_CHUNK) {
                synchronized (queue) {
                    queue.pollFirst();
                }

                //save the chunk right away instead of waiting for the next autosave. the chunk's light data has changed, and fp2 only rebuilds the tiles
                //  affected by a column when it is saved - without this, the freshly computed light wouldn't become visible for up to 45 seconds
                save(world, chunk);
            }
        }

        boolean empty;
        synchronized (queue) {
            empty = queue.isEmpty();
        }
        if (empty) {
            synchronized (QUEUES) {
                QUEUES.remove(world);
            }
        }
    }

    private static void save(@NonNull WorldServer world, @NonNull Chunk chunk) {
        try {
            //AnvilChunkLoader#saveChunk writes the chunk unconditionally, so there's no need to mark it as dirty first
            ((AnvilChunkLoader) world.getChunkProvider().chunkLoader).saveChunk(world, chunk);
        } catch (IOException e) {
            //the chunk will simply be saved by the next autosave instead
        }
    }

    private static final class Pending {
        final int x;
        final int z;
        int calls;

        Pending(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}
