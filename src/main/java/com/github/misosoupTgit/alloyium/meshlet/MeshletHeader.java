package com.github.misosoupTgit.alloyium.meshlet;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Boundary info only — no vertex repacking (DESIGN §2.2).
 * std430 layout, 48 bytes.
 */
public final class MeshletHeader {
    public static final int BYTES = 48;

    public int vertexOffset;
    public int vertexCount;
    public int primitiveOffset;
    public int primitiveCount;
    /** xyz = center, w = radius */
    public float sphereX, sphereY, sphereZ, sphereRadius;
    /** xyz = axis, w = cos(halfAngle); R1: w=2 disables cone cull */
    public float coneX, coneY, coneZ, coneW = 2.0f;

    /** Region key for draw grouping (not uploaded to GPU). */
    public int regionKey;
    /** Embeddium GL vertex buffer handle for this meshlet's region. */
    public int glVertexBuffer;
    /** Shared or region index buffer handle. */
    public int glIndexBuffer;

    public void write(ByteBuffer dst) {
        dst.putInt(vertexOffset);
        dst.putInt(vertexCount);
        dst.putInt(primitiveOffset);
        dst.putInt(primitiveCount);
        dst.putFloat(sphereX);
        dst.putFloat(sphereY);
        dst.putFloat(sphereZ);
        dst.putFloat(sphereRadius);
        dst.putFloat(coneX);
        dst.putFloat(coneY);
        dst.putFloat(coneZ);
        dst.putFloat(coneW);
    }

    public static ByteBuffer allocateUploadBuffer(int count) {
        return ByteBuffer.allocateDirect(count * BYTES).order(ByteOrder.nativeOrder());
    }
}
