package com.github.misosoupTgit.alloyium.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Frustum plane extraction for Minecraft 1.20+ (zero-to-one clip depth).
 * Evidence from CullDebug: classic GL [-1,1] far plane sat at |w|≈0.6 → ~97% frustum cull.
 * Matches JOML {@code FrustumIntersection#set(Matrix4fc, true)} conventions.
 */
public final class FrustumUtil {
    private FrustumUtil() {}

    /**
     * Fills out[0..23] as 6×vec4 (left,right,bottom,top,near,far).
     * Test camera-relative points: inside if {@code dot(n, p) + w >= -radius}.
     */
    public static void extractPlanes(Matrix4fc clip, float[] out24) {
        // left  = c3 + c0
        writePlane(out24, 0,
                clip.m03() + clip.m00(), clip.m13() + clip.m10(),
                clip.m23() + clip.m20(), clip.m33() + clip.m30());
        // right = c3 - c0
        writePlane(out24, 1,
                clip.m03() - clip.m00(), clip.m13() - clip.m10(),
                clip.m23() - clip.m20(), clip.m33() - clip.m30());
        // bottom = c3 + c1
        writePlane(out24, 2,
                clip.m03() + clip.m01(), clip.m13() + clip.m11(),
                clip.m23() + clip.m21(), clip.m33() + clip.m31());
        // top = c3 - c1
        writePlane(out24, 3,
                clip.m03() - clip.m01(), clip.m13() - clip.m11(),
                clip.m23() - clip.m21(), clip.m33() - clip.m31());
        // near (ZO): c2
        writePlane(out24, 4,
                clip.m02(), clip.m12(), clip.m22(), clip.m32());
        // far (ZO): c3 - c2
        writePlane(out24, 5,
                clip.m03() - clip.m02(), clip.m13() - clip.m12(),
                clip.m23() - clip.m22(), clip.m33() - clip.m32());
    }

    /** Convenience overload. */
    public static void extractPlanes(Matrix4f clip, float[] out24) {
        extractPlanes((Matrix4fc) clip, out24);
    }

    private static void writePlane(float[] out, int index, float x, float y, float z, float w) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 1e-8f) {
            x /= len;
            y /= len;
            z /= len;
            w /= len;
        }
        int o = index * 4;
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = z;
        out[o + 3] = w;
    }
}
