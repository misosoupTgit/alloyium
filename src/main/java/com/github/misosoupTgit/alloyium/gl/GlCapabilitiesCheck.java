package com.github.misosoupTgit.alloyium.gl;

import com.github.misosoupTgit.alloyium.Alloyium;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/** Detect OpenGL 4.3 + evaluate R4 IndirectParameters policy. */
public final class GlCapabilitiesCheck {
    private GlCapabilitiesCheck() {}

    public static void check() {
        GLCapabilities cap = GL.getCapabilities();
        boolean ok = cap.OpenGL43
                || (cap.GL_ARB_compute_shader
                && cap.GL_ARB_shader_storage_buffer_object
                && cap.GL_ARB_multi_draw_indirect
                && cap.GL_ARB_draw_indirect
                && cap.GL_ARB_explicit_attrib_location);

        IndirectParametersPolicy.evaluate(cap);

        Alloyium.IS_COMPATIBLE = ok;
        Alloyium.refreshEnabled();

        if (ok) {
            Alloyium.logVerbose("GL capability check passed (Compute + SSBO + MultiDrawIndirect)");
        } else {
            Alloyium.LOGGER.warn("GL capability check failed — Alloyium disabled; Embeddium default path remains");
        }
    }
}
