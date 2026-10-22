/*
 * Adapted from The MIT License (MIT)
 *
 * Copyright (c) 2018-2025 DaPorkchop_
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

import net.daporkchop.fp2.core.client.render.ShaderPackCompat;
import net.daporkchop.fp2.core.engine.EngineConstants;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Temporary diagnostics: collects a handful of per-frame timings and counters and dumps them to {@code fp2_prof.txt} once every couple of seconds.
 * <p>
 * This class is purely for development and is removed before the changes are committed.
 *
 * @author DaPorkchop_
 */
public final class RenderProfiler {
    private static final long INTERVAL_NANOS = 2_000_000_000L;

    private static final int[] LEVEL_TILES = new int[EngineConstants.MAX_LODS];
    private static final long[] LEVEL_INDICES = new long[EngineConstants.MAX_LODS];

    private static long startNanos = System.nanoTime();
    private static long lastFrameNanos;

    private static long prepareNanos;
    private static long bakeNanos;
    private static long renderNanos;
    private static int bakes;
    private static long bakesBytes;

    private static int frames;
    private static long frameIntervalNanos;

    private RenderProfiler() {
        throw new UnsupportedOperationException();
    }

    public static void prepare(long nanos) {
        prepareNanos += nanos;
    }

    public static void bake(long nanos, int tiles, long bytes) {
        bakeNanos += nanos;
        bakes += tiles;
        bakesBytes += bytes;
    }

    public static void tiles(int level, int tiles, long indices) {
        if (level >= 0 && level < LEVEL_TILES.length) {
            LEVEL_TILES[level] += tiles;
            LEVEL_INDICES[level] += indices;
        }
    }

    public static void render(long nanos) {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            frameIntervalNanos += now - lastFrameNanos;
            frames++;
        }
        lastFrameNanos = now;
        renderNanos += nanos;

        if (now - startNanos >= INTERVAL_NANOS) {
            flush(now);
        }
    }

    /**
     * Appends a single line to the profiler output file immediately.
     *
     * @param line the line to append
     */
    public static void note(String line) {
        try {
            File file = new File("../fp2_prof.txt").getAbsoluteFile();
            Files.write(file.toPath(), (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            //profiling must never break the game
        }
    }

    private static void flush(long now) {
        double seconds = (now - startNanos) / 1.0e9;
        double fps = frames == 0 ? 0.0 : frames / (frameIntervalNanos / 1.0e9);
        double frameMs = frames == 0 ? 0.0 : frameIntervalNanos / 1.0e6 / frames;

        StringBuilder out = new StringBuilder(512);
        out.append(String.format("t=%.1fs fps=%.1f frame=%.1fms | pack=%s | prepare=%.2fms (bake=%.2fms, %d tiles, %.1fMiB) | render=%.2fms | levels:",
                seconds, fps, frameMs, ShaderPackCompat.isActive() ? "on" : "off",
                prepareNanos / 1.0e6, bakeNanos / 1.0e6, bakes, bakesBytes / 1048576.0,
                renderNanos / 1.0e6));

        int tiles = 0;
        long indices = 0L;
        for (int level = 0; level < LEVEL_TILES.length; level++) {
            if (LEVEL_TILES[level] != 0 || LEVEL_INDICES[level] != 0L) {
                out.append(String.format(" L%d=%d/%.1fM", level, LEVEL_TILES[level], LEVEL_INDICES[level] / 1.0e6));
                tiles += LEVEL_TILES[level];
                indices += LEVEL_INDICES[level];
            }
        }
        out.append(String.format(" | total=%d tiles, %.1fM indices", tiles, indices / 1.0e6));

        try {
            File file = new File("../fp2_prof.txt").getAbsoluteFile();
            if (!file.exists() || file.length() < 4 << 20) { //don't let the file grow forever
                StringWriter writer = new StringWriter();
                PrintWriter printer = new PrintWriter(writer);
                printer.println(out);
                printer.flush();

                Files.write(file.toPath(), writer.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Throwable ignored) {
            //profiling must never break the game
        }

        //reset the per-interval counters
        startNanos = now;
        prepareNanos = bakeNanos = renderNanos = 0L;
        bakes = 0;
        bakesBytes = 0L;
        frames = 0;
        frameIntervalNanos = 0L;
        java.util.Arrays.fill(LEVEL_TILES, 0);
        java.util.Arrays.fill(LEVEL_INDICES, 0L);
    }
}
