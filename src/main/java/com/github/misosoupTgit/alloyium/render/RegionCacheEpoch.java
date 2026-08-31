package com.github.misosoupTgit.alloyium.render;

/**
 * Global generation for region command cache.
 * Bumped when Embeddium uploads/resizes/removes section meshes — enables cache hits
 * without re-fingerprinting while standing still.
 */
public final class RegionCacheEpoch {
    private static int epoch = 1;

    private RegionCacheEpoch() {}

    public static int current() {
        return epoch;
    }

    public static void bump() {
        epoch++;
    }
}
