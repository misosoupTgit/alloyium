package com.github.misosoupTgit.alloyium.debug;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.AlloyiumConfig;
import com.github.misosoupTgit.alloyium.render.HiZPyramid;

/**
 * DESIGN §5 counters. [Perf] log + detailed F3 only when {@code general.verboseLogging}.
 */
public final class PerfMetrics {
    private static final long LOG_INTERVAL_NS = 5_000_000_000L;

    private static int regions;
    private static int meshletsIn;
    private static int drawsIssued;
    private static int cpuPathRegions;
    private static int gpuPathRegions;
    private static int cacheHits;
    private static int epochHits;
    private static long commandBytes;
    private static boolean usedIndirectCount;
    private static long lastLogNs;

    public static volatile String f3Line = "";

    private PerfMetrics() {}

    public static void beginSolidPass() {
        regions = 0;
        meshletsIn = 0;
        drawsIssued = 0;
        cpuPathRegions = 0;
        gpuPathRegions = 0;
        cacheHits = 0;
        epochHits = 0;
        commandBytes = 0;
        usedIndirectCount = false;
    }

    public static void recordRegion(int meshletCount, int drawCount, boolean cpuPath, boolean indirectCount,
                                    boolean cacheHit, boolean epochHit) {
        regions++;
        meshletsIn += meshletCount;
        drawsIssued += Math.max(drawCount, 0);
        if (cacheHit) {
            cacheHits++;
        }
        if (epochHit) {
            epochHits++;
        }
        if (cpuPath) {
            cpuPathRegions++;
        } else {
            gpuPathRegions++;
        }
        usedIndirectCount |= indirectCount;
        commandBytes += (long) Math.max(drawCount, 0) * 20L;
    }

    public static void endSolidPass(HiZPyramid hiz) {
        if (!AlloyiumConfig.verboseLogging()) {
            f3Line = "";
            return;
        }
        String hizPart = !HiZPyramid.enabled() ? "HizOff"
                : (hiz != null && hiz.isReady()
                ? String.format("HizOK mip=%d %.2fms", hiz.lastBuildMips(), hiz.lastBuildNs() / 1_000_000.0)
                : "HizWarm");
        f3Line = String.format(
                "R6 in=%d draw~%d hit=%d/%d epoch=%d cpu=%d %s %s",
                meshletsIn, drawsIssued, cacheHits, regions, epochHits,
                cpuPathRegions,
                usedIndirectCount ? "CountARB" : "MultiDraw",
                hizPart
        );

        if (regions <= 0) {
            return;
        }
        long now = System.nanoTime();
        if (lastLogNs != 0L && (now - lastLogNs) < LOG_INTERVAL_NS) {
            return;
        }
        lastLogNs = now;
        Alloyium.LOGGER.info(
                "[Perf] in={} draw~{} reg={} cacheHit={}/{} epochHit={} cpuPath={} gpuPath={} cmdKB={} {} {}",
                meshletsIn, drawsIssued, regions, cacheHits, regions, epochHits,
                cpuPathRegions, gpuPathRegions,
                String.format("%.2f", commandBytes / 1024.0),
                usedIndirectCount ? "CountARB" : "MultiDraw",
                hizPart
        );
    }
}
