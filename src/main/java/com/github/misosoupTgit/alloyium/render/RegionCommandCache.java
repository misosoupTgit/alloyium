package com.github.misosoupTgit.alloyium.render;

import com.github.misosoupTgit.alloyium.gl.GpuBuffer;
import org.lwjgl.opengl.GL15C;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-region Indirect command cache (R6 overhead cut).
 * Hit when section geometry + camera block pos (face mask) unchanged — skips collect/cull/upload.
 * Toggle: config {@code general.regionCache}.
 */
public final class RegionCommandCache implements AutoCloseable {
    public static boolean enabled() {
        return com.github.misosoupTgit.alloyium.AlloyiumConfig.regionCache();
    }

    public static final class Entry {
        public long listSig;
        public long fingerprint;
        public int epoch;
        public int camX, camY, camZ;
        public final GpuBuffer commands = new GpuBuffer();
        public int drawCount;
        public int meshletIn;
        public int maxElements;
        public int glVbo;

        void upload(ByteBuffer cmdData, int draws, int meshlets, int maxElem, int vbo, long fp, long listSignature,
                    int camBlockX, int camBlockY, int camBlockZ) {
            long bytes = (long) draws * 20L;
            commands.ensureCapacity(Math.max(bytes, 256), GL15C.GL_DYNAMIC_DRAW);
            commands.uploadSub(0, cmdData);
            drawCount = draws;
            meshletIn = meshlets;
            maxElements = maxElem;
            glVbo = vbo;
            fingerprint = fp;
            listSig = listSignature;
            epoch = RegionCacheEpoch.current();
            camX = camBlockX;
            camY = camBlockY;
            camZ = camBlockZ;
        }

        boolean epochHit(int vbo, int camBlockX, int camBlockY, int camBlockZ, long listSignature) {
            return drawCount > 0
                    && glVbo == vbo
                    && epoch == RegionCacheEpoch.current()
                    && listSig == listSignature
                    && camX == camBlockX
                    && camY == camBlockY
                    && camZ == camBlockZ;
        }
    }

    private final Map<Long, Entry> entries = new HashMap<>();

    public Entry get(long key) {
        return entries.get(key);
    }

    public Entry getOrCreate(long key) {
        return entries.computeIfAbsent(key, k -> new Entry());
    }

    public static long regionKey(int originX, int originY, int originZ, int passHash) {
        long h = originX * 73428767L ^ originY * 19349663L ^ originZ * 83492791L;
        return h ^ ((long) passHash * 0x9E3779B97F4A7C15L);
    }

    public static long fnv(long h, int v) {
        h ^= (v & 0xffffffffL);
        return h * 1099511628211L;
    }

    public static long fnv(long h, long v) {
        h ^= v;
        return h * 1099511628211L;
    }

    @Override
    public void close() {
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            e.commands.close();
            it.remove();
        }
    }
}
