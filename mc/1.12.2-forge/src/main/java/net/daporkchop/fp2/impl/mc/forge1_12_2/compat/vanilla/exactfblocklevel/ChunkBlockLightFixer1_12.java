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
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.daporkchop.fp2.impl.mc.forge1_12_2.compat.vanilla.exactfblocklevel;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.Arrays;

/**
 * Workaround for vanilla's light engine being completely broken in dimensions without skylight.
 *
 * <p>{@link Chunk#checkLight()} does not compute any block light at all if the dimension has no skylight - it only sets the
 * {@code isLightPopulated} flag and pretends that everything is fine. In a normally played world this is not noticeable, because
 * chunks are only ever generated close to a player, and the vanilla light engine relights them while they are being ticked by
 * {@link net.minecraft.world.WorldServer#updateBlocks(int, int, boolean)}. fp2 however generates chunks which are arbitrarily far
 * away from any player and which are therefore never ticked; these chunks are written to disk with all block light set to zero,
 * and vanilla will never try to light them again because they are already flagged as being lit. The result is that fp2's far
 * terrain in the nether is rendered almost completely black, even though the chunk data itself is perfectly fine.</p>
 *
 * <p>This class contains a small, self-contained replacement for the block light part of vanilla's light engine, which is used to
 * light such chunks before they are written to disk. It deliberately mirrors the behaviour of
 * {@code World#getLightFromNeighborsFor(EnumSkyBlock, BlockPos)}: light is emitted by blocks, and spreads to neighbouring blocks
 * with an attenuation of {@code max(1, lightOpacity)}, while blocks with a light opacity of 15 or more are completely opaque.</p>
 *
 * <p>The implementation should produce light values which are identical to the ones vanilla would have computed, and is
 * therefore safe to use on chunks which may already have been (partially) lit.</p>
 *
 * @author DaPorkchop_
 */
public final class ChunkBlockLightFixer1_12 {
    /**
     * The maximum distance which block light is able to travel, and therefore the number of blocks outside of a chunk which
     * have to be taken into account when computing the light inside of it.
     */
    private static final int MAX_LIGHT_DISTANCE = 15;

    private static final ThreadLocal<Buffer> BUFFER = ThreadLocal.withInitial(Buffer::new);

    private ChunkBlockLightFixer1_12() {
        //no-op
    }

    /**
     * Checks whether the given chunk contains any block which emits light, but whose block light is zero.
     *
     * <p>This can only ever happen if the chunk was never lit at all, and allows us to avoid relighting chunks whose light is
     * already correct.</p>
     *
     * @param chunk  the chunk to check
     * @param height the height of the chunk
     * @return whether the given chunk needs to be relit
     */
    @SuppressWarnings("deprecation")
    public static boolean needsRelight(Chunk chunk, int height) {
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        for (int sectionY = 0, maxSectionY = Math.min((height + 15) >> 4, sections.length); sectionY < maxSectionY; sectionY++) {
            ExtendedBlockStorage section = sections[sectionY];
            if (section == null) { //no blocks, and therefore no light emitters
                continue;
            }

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        IBlockState state = section.get(x, y, z);
                        if (state.getLightValue() > 0 && section.getBlockLight(x, y, z) == 0) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Recomputes the block light of the given chunk, and stores it into the chunk.
     *
     * <p>The 3x3 chunk area around the given chunk is loaded as needed. The chunk will be marked as modified, but not saved.</p>
     *
     * @param world  the world the chunk is in
     * @param chunk  the chunk to relight
     * @param height the height of the chunk
     */
    public static void relight(WorldServer world, Chunk chunk, int height) {
        final int chunkX = chunk.x;
        final int chunkZ = chunk.z;

        final int radius = MAX_LIGHT_DISTANCE;
        final int size = 16 + (radius << 1);
        final int minX = (chunkX << 4) - radius;
        final int minZ = (chunkZ << 4) - radius;

        Buffer buffer = BUFFER.get();
        byte[] light = buffer.light(size, height);

        //grab the chunks covering the area we're about to scan
        Chunk[] chunks = new Chunk[9];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunks[(dx + 1) * 3 + (dz + 1)] = world.getChunk(chunkX + dx, chunkZ + dz);
            }
        }

        //seed the light array with the emission of every light-emitting block in the area
        buffer.tail = 0;
        for (int lx = 0; lx < size; lx++) {
            int x = minX + lx;
            for (int lz = 0; lz < size; lz++) {
                int z = minZ + lz;
                Chunk column = chunks[(((x >> 4) - chunkX) + 1) * 3 + (((z >> 4) - chunkZ) + 1)];

                int base = (lx * size + lz) * height;
                for (int y = 0; y < height; y++) {
                    int emission = column.getBlockState(x & 15, y, z & 15).getLightValue();
                    if (emission > 0) {
                        light[base + y] = (byte) emission;
                        buffer.push(base + y);
                    }
                }
            }
        }

        //flood fill
        for (int head = 0; head < buffer.tail; head++) {
            int index = buffer.queue[head];
            int level = light[index];
            if (level <= 1) { //light can't spread any further
                continue;
            }

            int y = index % height;
            int rem = index / height;
            int lz = rem % size;
            int lx = rem / size;

            if (lx > 0) {
                spread(light, buffer, chunks, chunkX, chunkZ, minX, minZ, size, height, lx - 1, lz, y, level);
            }
            if (lx + 1 < size) {
                spread(light, buffer, chunks, chunkX, chunkZ, minX, minZ, size, height, lx + 1, lz, y, level);
            }
            if (lz > 0) {
                spread(light, buffer, chunks, chunkX, chunkZ, minX, minZ, size, height, lx, lz - 1, y, level);
            }
            if (lz + 1 < size) {
                spread(light, buffer, chunks, chunkX, chunkZ, minX, minZ, size, height, lx, lz + 1, y, level);
            }
            if (y > 0) {
                spread(light, buffer, chunks, chunkX, chunkZ, minX, minZ, size, height, lx, lz, y - 1, level);
            }
            if (y + 1 < height) {
                spread(light, buffer, chunks, chunkX, chunkZ, minX, minZ, size, height, lx, lz, y + 1, level);
            }
        }

        //store the new light values into the chunk
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        BlockPos.PooledMutableBlockPos pos = BlockPos.PooledMutableBlockPos.retain();
        try {
            for (int x = 0; x < 16; x++) {
                int worldX = (chunkX << 4) + x;
                for (int z = 0; z < 16; z++) {
                    int worldZ = (chunkZ << 4) + z;

                    int base = ((radius + x) * size + (radius + z)) * height;
                    for (int y = 0; y < height; y++) {
                        int value = light[base + y];
                        if (value == 0 && (y >> 4) < sections.length && sections[y >> 4] == null) {
                            continue; //don't create empty sections just to store darkness in them
                        }
                        chunk.setLightFor(EnumSkyBlock.BLOCK, pos.setPos(worldX, y, worldZ), value);
                    }
                }
            }
        } finally {
            pos.release();
        }
    }

    @SuppressWarnings("deprecation")
    private static void spread(byte[] light, Buffer buffer, Chunk[] chunks, int chunkX, int chunkZ,
                               int minX, int minZ, int size, int height,
                               int lx, int lz, int y, int level) {
        int index = (lx * size + lz) * height + y;

        int opacity = chunks[((((minX + lx) >> 4) - chunkX) + 1) * 3 + ((((minZ + lz) >> 4) - chunkZ) + 1)]
                .getBlockState((minX + lx) & 15, y, (minZ + lz) & 15).getLightOpacity();
        if (opacity >= 15) { //block is completely opaque, no light can pass through it
            return;
        }

        int next = level - (opacity < 1 ? 1 : opacity);
        if (next > light[index]) {
            light[index] = (byte) next;
            buffer.push(index);
        }
    }

    /**
     * Reusable per-thread scratch buffer.
     *
     * @author DaPorkchop_
     */
    private static final class Buffer {
        private byte[] light = new byte[0];
        private int[] queue = new int[1 << 16];
        private int tail;

        byte[] light(int size, int height) {
            int length = size * size * height;
            if (this.light.length < length) {
                this.light = new byte[length];
            } else {
                Arrays.fill(this.light, 0, length, (byte) 0);
            }
            return this.light;
        }

        void push(int index) {
            if (this.tail == this.queue.length) {
                this.queue = Arrays.copyOf(this.queue, this.queue.length << 1);
            }
            this.queue[this.tail++] = index;
        }
    }
}
