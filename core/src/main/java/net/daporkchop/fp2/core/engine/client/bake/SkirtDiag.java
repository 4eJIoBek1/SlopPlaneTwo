// TEMP DIAG — entire file is temporary diagnostic, remove before stable commit
/*
 * TEMPORARY diagnostic helper and Seam Profiler for FarPlaneTwo.
 */
package net.daporkchop.fp2.core.engine.client.bake;

import net.daporkchop.fp2.core.engine.TilePos;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static net.daporkchop.fp2.core.engine.EngineConstants.POS_FRACT_SHIFT;
import static net.daporkchop.fp2.core.engine.EngineConstants.T_SHIFT;

/**
 * Diagnostic and profiling tool for FarPlaneTwo terrain seams and connective geometry.
 */
public final class SkirtDiag {
    public static final class SeamRecord {
        public final TilePos pos;
        public final boolean xAxis;
        public final int walls;
        public final int planeCoord; // world X for xAxis, world Z for zAxis
        public final int minPerp;    // min world Z for xAxis, min world X for zAxis
        public final int maxPerp;    // max world Z for xAxis, max world X for zAxis
        public final int worldMinY;
        public final int worldMaxY;
        public final long timestamp;

        public SeamRecord(TilePos pos, boolean xAxis, int walls, int planeCoord, int minPerp, int maxPerp, int worldMinY, int worldMaxY) {
            this.pos = pos;
            this.xAxis = xAxis;
            this.walls = walls;
            this.planeCoord = planeCoord;
            this.minPerp = minPerp;
            this.maxPerp = maxPerp;
            this.worldMinY = worldMinY;
            this.worldMaxY = worldMaxY;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final Map<Long, SeamRecord> ACTIVE_SEAMS = new ConcurrentHashMap<>();
    private static final AtomicLong TILES = new AtomicLong();
    private static final AtomicLong MISSING = new AtomicLong();
    private static final AtomicLong MISSING_BORDER_X = new AtomicLong();
    private static final AtomicLong MISSING_BORDER_Z = new AtomicLong();
    private static final AtomicLong WALLS = new AtomicLong();
    private static final AtomicLong WALLS_X = new AtomicLong();
    private static final AtomicLong WALLS_Z = new AtomicLong();

    private static volatile double camX, camY, camZ;
    private static volatile double lookX, lookY, lookZ;
    private static volatile boolean camValid;

    private static final int MAX_TILE_LINES = 2000;
    private static int tileLines;

    private SkirtDiag() {
    }

    public static void clear() {
        ACTIVE_SEAMS.clear();
        WALLS.set(0);
        WALLS_X.set(0);
        WALLS_Z.set(0);
        MISSING.set(0);
        MISSING_BORDER_X.set(0);
        MISSING_BORDER_Z.set(0);
        TILES.set(0);
        tileLines = 0;
    }

    public static void updateCamera(double px, double py, double pz, double lx, double ly, double lz) {
        camX = px;
        camY = py;
        camZ = pz;
        lookX = lx;
        lookY = ly;
        lookZ = lz;
        camValid = true;
    }

    public static void missing(boolean borderX, boolean borderZ) {
        MISSING.incrementAndGet();
        if (borderX) {
            MISSING_BORDER_X.incrementAndGet();
        }
        if (borderZ) {
            MISSING_BORDER_Z.incrementAndGet();
        }
    }

    private static long seamKey(TilePos pos, boolean xAxis) {
        // Pack (level: 5 bits, x: 20 bits, y: 18 bits, z: 20 bits, axis: 1 bit) into 64 bits
        long k = ((long) pos.level() & 0x1FL) << 59;
        k |= (((long) pos.x() & 0xFFFFFL) << 39);
        k |= (((long) pos.y() & 0x3FFFFL) << 21);
        k |= (((long) pos.z() & 0xFFFFFL) << 1);
        k |= (xAxis ? 1L : 0L);
        return k;
    }

    public static void recordSeam(TilePos pos, boolean xAxis, int walls, int[] colLow, int[] colHigh) {
        if (walls <= 0) {
            return;
        }
        WALLS.addAndGet(walls);
        if (xAxis) {
            WALLS_X.addAndGet(walls);
        } else {
            WALLS_Z.addAndGet(walls);
        }

        int minLow = Integer.MAX_VALUE;
        int maxHigh = Integer.MIN_VALUE;
        for (int i = 0; i < colLow.length; i++) {
            if (colHigh[i] > colLow[i]) {
                minLow = Math.min(minLow, colLow[i]);
                maxHigh = Math.max(maxHigh, colHigh[i]);
            }
        }
        if (minLow == Integer.MAX_VALUE) {
            minLow = 0;
            maxHigh = 0;
        }

        int level = pos.level();
        int tileSpan = 16 << level;
        int worldMinY = pos.minBlockY() + ((minLow >> POS_FRACT_SHIFT) << level);
        int worldMaxY = pos.minBlockY() + ((maxHigh >> POS_FRACT_SHIFT) << level);

        int planeCoord = xAxis ? (pos.minBlockX() + tileSpan) : (pos.minBlockZ() + tileSpan);
        int minPerp = xAxis ? pos.minBlockZ() : pos.minBlockX();
        int maxPerp = minPerp + tileSpan;

        SeamRecord rec = new SeamRecord(pos, xAxis, walls, planeCoord, minPerp, maxPerp, worldMinY, worldMaxY);
        ACTIVE_SEAMS.put(seamKey(pos, xAxis), rec);

        if (ACTIVE_SEAMS.size() > 10000) {
            long cutoff = System.currentTimeMillis() - 45_000L;
            ACTIVE_SEAMS.entrySet().removeIf(e -> e.getValue().timestamp < cutoff);
        }
    }

    public static void tile(TilePos pos, int gaps, int walls, int above, int fallbacks) {
        long tiles = TILES.incrementAndGet();
        if ((tiles % 20000L) == 0L) {
            write("STATS tiles=" + tiles
                    + " missing=" + MISSING.get() + " (borderX=" + MISSING_BORDER_X.get() + " borderZ=" + MISSING_BORDER_Z.get() + ')'
                    + " totalWalls=" + WALLS.get() + " (x=" + WALLS_X.get() + " z=" + WALLS_Z.get() + ")\n");
        }

        if (pos.level() == 0 && (gaps > 0 || walls > 0) && tileLines++ < MAX_TILE_LINES) {
            write("tile " + pos.x() + ' ' + pos.y() + ' ' + pos.z()
                    + " at=" + (pos.x() << 4) + ' ' + (pos.y() << 4) + ' ' + (pos.z() << 4)
                    + " gaps=" + gaps + " walls=" + walls + '\n');
        }
    }

    public static SeamHit findLookSeam(double maxDist) {
        if (!camValid) {
            return null;
        }

        double bestDist = maxDist;
        SeamRecord bestRec = null;

        for (SeamRecord rec : ACTIVE_SEAMS.values()) {
            double plane = (double) rec.planeCoord;
            double t;
            double hitPerp;
            double hitY;

            if (rec.xAxis) {
                if (Math.abs(lookX) < 1e-5) {
                    continue;
                }
                t = (plane - camX) / lookX;
                if (t <= 0.0 || t >= bestDist) {
                    continue;
                }
                hitPerp = camZ + lookZ * t;
                hitY = camY + lookY * t;
            } else {
                if (Math.abs(lookZ) < 1e-5) {
                    continue;
                }
                t = (plane - camZ) / lookZ;
                if (t <= 0.0 || t >= bestDist) {
                    continue;
                }
                hitPerp = camX + lookX * t;
                hitY = camY + lookY * t;
            }

            // Margin of 2.0 blocks around the seam boundary
            if (hitPerp >= (rec.minPerp - 2.0) && hitPerp <= (rec.maxPerp + 2.0)
                    && hitY >= (rec.worldMinY - 2.0) && hitY <= (rec.worldMaxY + 2.0)) {
                bestDist = t;
                bestRec = rec;
            }
        }

        return bestRec != null ? new SeamHit(bestRec, bestDist) : null;
    }

    public static final class SeamHit {
        public final SeamRecord record;
        public final double distance;

        public SeamHit(SeamRecord record, double distance) {
            this.record = record;
            this.distance = distance;
        }
    }

    public static String getF3SeamInfo() {
        SeamHit hit = findLookSeam(384.0);
        if (hit != null) {
            SeamRecord r = hit.record;
            return "Seam look: " + (r.xAxis ? "+X" : "+Z")
                    + " @ (" + r.pos.x() + ',' + r.pos.y() + ',' + r.pos.z() + " L" + r.pos.level() + ')'
                    + " dist=" + (int) hit.distance + "m"
                    + " " + r.walls + "w"
                    + " (Y " + r.worldMinY + ".." + r.worldMaxY + ')';
        }

        return "Seams: " + WALLS.get() + "w (+X:" + WALLS_X.get() + " +Z:" + WALLS_Z.get() + ") | " + ACTIVE_SEAMS.size() + " active";
    }

    public static String getDetailedChatReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6[Seam Profiler]§r Total walls: §a").append(WALLS.get())
          .append("§r (+X: §b").append(WALLS_X.get()).append("§r, +Z: §b").append(WALLS_Z.get()).append("§r)\n");

        if (!camValid) {
            sb.append("§7Camera position not yet sampled (open F3 once).");
            return sb.toString();
        }

        int playerTx = ((int) Math.floor(camX)) >> 4;
        int playerTy = ((int) Math.floor(camY)) >> 4;
        int playerTz = ((int) Math.floor(camZ)) >> 4;
        sb.append("§ePlayer at tile (").append(playerTx).append(',').append(playerTy).append(',').append(playerTz)
          .append(") block (").append((int) camX).append(',').append((int) camY).append(',').append((int) camZ).append(")\n");

        SeamHit hit = findLookSeam(384.0);
        if (hit != null) {
            SeamRecord r = hit.record;
            sb.append(" §f» §dDirect crosshair hit:§r ")
              .append(r.xAxis ? "+X seam" : "+Z seam")
              .append(" of Tile (").append(r.pos.x()).append(',').append(r.pos.y()).append(',').append(r.pos.z()).append(" L").append(r.pos.level()).append(')')
              .append(" at dist §e").append((int) hit.distance).append("m§r\n")
              .append("    Walls: §a").append(r.walls).append(" quads§r, World Y: §b").append(r.worldMinY).append("..").append(r.worldMaxY)
              .append("§r, Plane: §7").append(r.xAxis ? "X=" : "Z=").append(r.planeCoord).append(" (bounds ").append(r.minPerp).append("..").append(r.maxPerp).append(")\n");
        } else {
            sb.append(" §7No seam directly intersected by crosshair within 384m.\n");
        }

        sb.append("§7Active seam records in memory: §f").append(ACTIVE_SEAMS.size());
        return sb.toString();
    }

    private static synchronized void write(String s) {
        try (FileWriter writer = new FileWriter("C:/Users/GM/Desktop/fp2f/fp2_skirt.txt", true)) {
            writer.write(s);
        } catch (IOException ignored) {
        }
    }
}
