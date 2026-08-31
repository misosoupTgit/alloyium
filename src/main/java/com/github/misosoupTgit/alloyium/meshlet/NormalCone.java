package com.github.misosoupTgit.alloyium.meshlet;

import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;

/**
 * Axis-aligned facing → normal cone (xyz = axis, w = cos(halfAngle)).
 * UNASSIGNED disables cone (w &gt; 1).
 */
public final class NormalCone {
    /** ~15° half-angle — tight for Embeddium face-sliced geometry. */
    private static final float COS_HALF_ANGLE = 0.9659258f;

    private NormalCone() {}

    public static void applyFacing(MeshletHeader header, int facingOrdinal) {
        switch (facingOrdinal) {
            case 0 -> set(header, 1f, 0f, 0f, COS_HALF_ANGLE);  // POS_X
            case 1 -> set(header, 0f, 1f, 0f, COS_HALF_ANGLE);  // POS_Y
            case 2 -> set(header, 0f, 0f, 1f, COS_HALF_ANGLE);  // POS_Z
            case 3 -> set(header, -1f, 0f, 0f, COS_HALF_ANGLE); // NEG_X
            case 4 -> set(header, 0f, -1f, 0f, COS_HALF_ANGLE); // NEG_Y
            case 5 -> set(header, 0f, 0f, -1f, COS_HALF_ANGLE); // NEG_Z
            default -> set(header, 0f, 1f, 0f, 2.0f);           // UNASSIGNED — disable
        }
    }

    public static void applyFacing(MeshletHeader header, ModelQuadFacing facing) {
        applyFacing(header, facing.ordinal());
    }

    private static void set(MeshletHeader h, float x, float y, float z, float w) {
        h.coneX = x;
        h.coneY = y;
        h.coneZ = z;
        h.coneW = w;
    }
}
