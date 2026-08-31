package com.github.misosoupTgit.alloyium.compute;

import com.github.misosoupTgit.alloyium.meshlet.MeshletFrameList;
import com.github.misosoupTgit.alloyium.meshlet.MeshletHeader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * CPU Indirect command build (Compute B' contract: offsets only).
 * Default skips frustum/cone — Embeddium list + face mask already culled.
 * Toggle: config {@code general.cpuCull}.
 */
public final class CpuIndirectBuilder {
    public static boolean cpuCull() {
        return com.github.misosoupTgit.alloyium.AlloyiumConfig.cpuCull();
    }

    private CpuIndirectBuilder() {}

    public static int build(MeshletFrameList list, float[] planes24, float camX, float camY, float camZ,
                            ByteBuffer outCommands) {
        if (!cpuCull()) {
            return buildAll(list, outCommands);
        }
        outCommands.clear();
        int visible = 0;
        for (int i = 0, n = list.size(); i < n; i++) {
            MeshletHeader h = list.headers().get(i);
            if (com.github.misosoupTgit.alloyium.AlloyiumConfig.cullFrustum() && frustumCull(h, planes24, camX, camY, camZ)) {
                continue;
            }
            if (com.github.misosoupTgit.alloyium.AlloyiumConfig.cullCone() && coneCull(h, camX, camY, camZ)) {
                continue;
            }
            writeCommand(outCommands, h);
            visible++;
        }
        outCommands.flip();
        return visible;
    }

    public static int buildAll(MeshletFrameList list, ByteBuffer outCommands) {
        outCommands.clear();
        int n = list.size();
        for (int i = 0; i < n; i++) {
            writeCommand(outCommands, list.headers().get(i));
        }
        outCommands.flip();
        return n;
    }

    private static void writeCommand(ByteBuffer out, MeshletHeader h) {
        out.putInt(h.primitiveCount * 3);
        out.putInt(1);
        out.putInt(h.primitiveOffset);
        out.putInt(h.vertexOffset);
        out.putInt(0);
    }

    public static ByteBuffer ensureCapacity(ByteBuffer buf, int drawCount) {
        int need = drawCount * ComputePipeline.COMMAND_BYTES;
        if (buf != null && buf.capacity() >= need) {
            buf.clear();
            return buf;
        }
        return ByteBuffer.allocateDirect(Math.max(need, 4096)).order(ByteOrder.nativeOrder());
    }

    private static boolean frustumCull(MeshletHeader h, float[] planes, float camX, float camY, float camZ) {
        float cx = h.sphereX - camX;
        float cy = h.sphereY - camY;
        float cz = h.sphereZ - camZ;
        float r = h.sphereRadius;
        for (int i = 0; i < 6; i++) {
            int o = i * 4;
            if (planes[o] * cx + planes[o + 1] * cy + planes[o + 2] * cz + planes[o + 3] < -r) {
                return true;
            }
        }
        return false;
    }

    private static boolean coneCull(MeshletHeader h, float camX, float camY, float camZ) {
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
        return h.coneX * dx * inv + h.coneY * dy * inv + h.coneZ * dz * inv > h.coneW;
    }
}
