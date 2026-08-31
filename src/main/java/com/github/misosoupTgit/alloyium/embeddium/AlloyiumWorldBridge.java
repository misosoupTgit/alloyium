package com.github.misosoupTgit.alloyium.embeddium;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.render.AlloyiumTerrainRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

/** Owns the R1 terrain renderer lifecycle for the active world. */
public final class AlloyiumWorldBridge {
    private static AlloyiumTerrainRenderer renderer;

    private AlloyiumWorldBridge() {}

    public static void create(ChunkVertexType vertexType) {
        destroy();
        if (!Alloyium.IS_ENABLED) {
            return;
        }
        renderer = new AlloyiumTerrainRenderer(vertexType);
    }

    public static void destroy() {
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
    }

    public static AlloyiumTerrainRenderer get() {
        return renderer;
    }

    public static boolean active() {
        return Alloyium.IS_ENABLED && renderer != null;
    }
}
