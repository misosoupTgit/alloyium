package com.github.misosoupTgit.alloyium.debug;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.meshlet.MeshletFrameList;
import com.github.misosoupTgit.alloyium.meshlet.MeshletHeader;

/**
 * Cull diagnostics: CPU mirror vs GPU counters, F3 lines, throttled logs.
 * Opt-in: config {@code debug.debugCull} (readback stalls the GPU).
 */
public final class CullDebug {
    public static boolean logEnabled() {
        return com.github.misosoupTgit.alloyium.AlloyiumConfig.debugCull();
    }

    public static boolean enableFrustum() {
        return com.github.misosoupTgit.alloyium.AlloyiumConfig.cullFrustum();
    }

    public static boolean enableCone() {
        return com.github.misosoupTgit.alloyium.AlloyiumConfig.cullCone();
    }

    private static final int LOG_EVERY_FRAMES = 120; // ~2s at 60fps; was too chatty at 60

    // Frame accumulators (sum over regions in one solid pass)
    private static int frameInput;
    private static int frameGpuVisible;
    private static int frameGpuFrustum;
    private static int frameGpuCone;
    private static int frameCpuVisible;
    private static int frameCpuFrustum;
    private static int frameCpuCone;
    private static int frameRegions;
    private static float[] lastPlanes = new float[24];
    private static float lastCamX, lastCamY, lastCamZ;
    private static int frameCounter;

    private static int frameGpuOcclusion;

    // Published for F3
    public static volatile String f3Line0 = "Alloyium: Compute B' R6";
    public static volatile String f3Line1 = "";
    public static volatile String f3Line2 = "";

    private CullDebug() {}

    public static void beginSolidPass() {
        if (!logEnabled()) {
            return;
        }
        frameInput = 0;
        frameGpuVisible = 0;
        frameGpuFrustum = 0;
        frameGpuCone = 0;
        frameGpuOcclusion = 0;
        frameCpuVisible = 0;
        frameCpuFrustum = 0;
        frameCpuCone = 0;
        frameRegions = 0;
    }

    public static CpuCullResult mirrorCpu(MeshletFrameList list, float[] planes24, float camX, float camY, float camZ) {
        int frustum = 0, cone = 0, visible = 0;
        for (MeshletHeader h : list.headers()) {
            if (enableFrustum() && cpuFrustumCull(h, planes24, camX, camY, camZ)) {
                frustum++;
                continue;
            }
            if (enableCone() && cpuConeCull(h, camX, camY, camZ)) {
                cone++;
                continue;
            }
            visible++;
        }
        return new CpuCullResult(list.size(), visible, frustum, cone);
    }

    public static void recordRegion(CpuCullResult cpu, int gpuVisible, int gpuFrustum, int gpuCone, int gpuOcclusion,
                                    float[] planes24, float camX, float camY, float camZ) {
        if (!logEnabled()) {
            return;
        }
        frameInput += cpu.input;
        frameCpuVisible += cpu.visible;
        frameCpuFrustum += cpu.frustumCulled;
        frameCpuCone += cpu.coneCulled;
        frameGpuVisible += gpuVisible;
        frameGpuFrustum += gpuFrustum;
        frameGpuCone += gpuCone;
        frameGpuOcclusion += gpuOcclusion;
        frameRegions++;
        System.arraycopy(planes24, 0, lastPlanes, 0, 24);
        lastCamX = camX;
        lastCamY = camY;
        lastCamZ = camZ;
    }

    public static void endSolidPass() {
        boolean verbose = com.github.misosoupTgit.alloyium.AlloyiumConfig.verboseLogging();
        if (!logEnabled()) {
            if (verbose) {
                f3Line0 = String.format("Alloyium F%s C%s H%s cache%s",
                        enableFrustum() ? "on" : "off", enableCone() ? "on" : "off",
                        com.github.misosoupTgit.alloyium.render.HiZPyramid.enabled() ? "on" : "off",
                        com.github.misosoupTgit.alloyium.render.RegionCommandCache.enabled() ? "on" : "off");
            } else {
                f3Line0 = Alloyium.IS_ENABLED ? "Alloyium ON" : "Alloyium OFF";
            }
            f3Line1 = "";
            f3Line2 = "";
            return;
        }
        frameCounter++;
        f3Line0 = String.format("Alloyium R6 F%s C%s | in=%d visGPU=%d",
                enableFrustum() ? "on" : "off", enableCone() ? "on" : "off",
                frameInput, frameGpuVisible);
        f3Line1 = verbose
                ? String.format("GPU cull: F=%d C=%d O=%d | CPU F=%d C=%d | Δvis=%d",
                frameGpuFrustum, frameGpuCone, frameGpuOcclusion, frameCpuFrustum, frameCpuCone,
                frameGpuVisible - frameCpuVisible)
                : "";
        f3Line2 = verbose
                ? String.format("cam=(%.1f,%.1f,%.1f) regions=%d", lastCamX, lastCamY, lastCamZ, frameRegions)
                : "";

        if (!verbose || frameInput == 0) {
            return;
        }

        boolean overCull = frameGpuVisible * 10 < frameInput;
        boolean mismatch = Math.abs(frameGpuVisible - frameCpuVisible) > 0
                || Math.abs(frameGpuFrustum - frameCpuFrustum) > 0
                || Math.abs(frameGpuCone - frameCpuCone) > 0;

        if ((frameCounter % LOG_EVERY_FRAMES) != 0) {
            return;
        }

        Alloyium.LOGGER.info(
                "[CullDebug] in={} gpuVis={} gpuF={} gpuC={} cpuVis={} cpuF={} cpuC={} deltaVis={} regions={}",
                frameInput, frameGpuVisible, frameGpuFrustum, frameGpuCone,
                frameCpuVisible, frameCpuFrustum, frameCpuCone,
                frameGpuVisible - frameCpuVisible, frameRegions
        );
        Alloyium.LOGGER.info("[CullDebug] cam=({}, {}, {}) farPlaneW={}",
                lastCamX, lastCamY, lastCamZ, lastPlanes[5 * 4 + 3]);
        for (int i = 0; i < 6; i++) {
            int o = i * 4;
            Alloyium.LOGGER.info("[CullDebug] plane[{}] = ({}, {}, {}, {})",
                    i, lastPlanes[o], lastPlanes[o + 1], lastPlanes[o + 2], lastPlanes[o + 3]);
        }
        if (mismatch) {
            Alloyium.LOGGER.warn("[CullDebug] CPU/GPU cull mismatch — investigate plane space or cone math");
        }
        if (overCull) {
            Alloyium.LOGGER.warn("[CullDebug] GPU kept <10% of meshlets ({} / {}) — likely over-cull",
                    frameGpuVisible, frameInput);
        }
    }

    private static boolean cpuFrustumCull(MeshletHeader h, float[] planes, float camX, float camY, float camZ) {
        float cx = h.sphereX - camX;
        float cy = h.sphereY - camY;
        float cz = h.sphereZ - camZ;
        float r = h.sphereRadius;
        for (int i = 0; i < 6; i++) {
            int o = i * 4;
            float d = planes[o] * cx + planes[o + 1] * cy + planes[o + 2] * cz + planes[o + 3];
            if (d < -r) {
                return true;
            }
        }
        return false;
    }

    private static boolean cpuConeCull(MeshletHeader h, float camX, float camY, float camZ) {
        if (h.coneW > 1.0f) {
            return false;
        }
        float dx = h.sphereX - camX;
        float dy = h.sphereY - camY;
        float dz = h.sphereZ - camZ;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) {
            return false;
        }
        float inv = 1.0f / len;
        float dot = h.coneX * dx * inv + h.coneY * dy * inv + h.coneZ * dz * inv;
        return dot > h.coneW;
    }

    public record CpuCullResult(int input, int visible, int frustumCulled, int coneCulled) {}
}
