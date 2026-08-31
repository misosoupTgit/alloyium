package com.github.misosoupTgit.alloyium.render;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.gl.ShaderProgram;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;

/**
 * R5 temporal Hi-Z (opt-in). Default OFF — full-res build + forcing GPU path tanked FPS on R5.0.
 * Toggle: config {@code hiz.*}.
 */
public final class HiZPyramid implements AutoCloseable {
    public static boolean enabled() {
        return com.github.misosoupTgit.alloyium.AlloyiumConfig.hiz();
    }

    public static float bias() {
        return com.github.misosoupTgit.alloyium.AlloyiumConfig.hizBias();
    }

    public static int downsample() {
        return Math.max(1, com.github.misosoupTgit.alloyium.AlloyiumConfig.hizDownsample());
    }

    public static int buildEvery() {
        return Math.max(1, com.github.misosoupTgit.alloyium.AlloyiumConfig.hizEvery());
    }

    private static final float INVALIDATE_CAM_DELTA = 48.0f;

    private final ShaderProgram copyProgram;
    private final ShaderProgram mipProgram;
    private final int uCopySize;
    private final int uCopyScale;
    private final int uMipDstSize;
    private final int uMipSrcSize;

    private int texture;
    private int levels;
    private int width;
    private int height;
    private boolean ready;
    private float lastCamX = Float.NaN, lastCamY, lastCamZ;
    private long lastBuildNs;
    private int lastBuildMips;
    private int frameCounter;

    public HiZPyramid() {
        this.copyProgram = ShaderProgram.compileCompute("/assets/alloyium/shaders/compute_hiz_copy.glsl");
        this.mipProgram = ShaderProgram.compileCompute("/assets/alloyium/shaders/compute_hiz_mip.glsl");
        this.uCopySize = copyProgram.uniformLocation("uSize");
        this.uCopyScale = copyProgram.uniformLocation("uScale");
        this.uMipDstSize = mipProgram.uniformLocation("uDstSize");
        this.uMipSrcSize = mipProgram.uniformLocation("uSrcSize");
        Alloyium.logVerbose(
                "Hi-Z R5 enabled (bias={}, downsample={}, every={} frames)",
                bias(), downsample(), buildEvery()
        );
    }

    public boolean isReady() {
        return enabled() && ready && texture != 0;
    }

    public int maxLevel() {
        return Math.max(0, levels - 1);
    }

    public long lastBuildNs() {
        return lastBuildNs;
    }

    public int lastBuildMips() {
        return lastBuildMips;
    }

    public void noteCamera(float camX, float camY, float camZ) {
        if (!enabled()) {
            return;
        }
        if (!Float.isNaN(lastCamX)) {
            float dx = camX - lastCamX, dy = camY - lastCamY, dz = camZ - lastCamZ;
            if (dx * dx + dy * dy + dz * dz > INVALIDATE_CAM_DELTA * INVALIDATE_CAM_DELTA) {
                ready = false;
            }
        }
        lastCamX = camX;
        lastCamY = camY;
        lastCamZ = camZ;
    }

    /** After solid terrain: copy depth (downsampled) and build max-mip pyramid. */
    public void captureAfterSolids() {
        if (!enabled() || !Alloyium.IS_ENABLED) {
            return;
        }
        frameCounter++;
        if (ready && (frameCounter % buildEvery()) != 0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null) {
            return;
        }
        int fullW = main.width;
        int fullH = main.height;
        if (fullW <= 0 || fullH <= 0) {
            return;
        }
        int ds = downsample();
        int w = Math.max(1, fullW / ds);
        int h = Math.max(1, fullH / ds);

        long t0 = System.nanoTime();
        ensureTexture(w, h);

        int depthTex = main.getDepthTextureId();
        int prevProg = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevActive = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int prevTex = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);

        copyProgram.use();
        if (uCopySize >= 0) {
            GL20C.glUniform2i(uCopySize, w, h);
        }
        if (uCopyScale >= 0) {
            GL20C.glUniform1i(uCopyScale, ds);
        }
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depthTex);
        int prevCompare = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_COMPARE_MODE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
        GL42C.glBindImageTexture(1, texture, 0, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
        GL43C.glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
        GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_COMPARE_MODE, prevCompare);

        int srcW = w, srcH = h;
        for (int level = 1; level < levels; level++) {
            int dstW = Math.max(1, srcW / 2);
            int dstH = Math.max(1, srcH / 2);
            mipProgram.use();
            if (uMipSrcSize >= 0) {
                GL20C.glUniform2i(uMipSrcSize, srcW, srcH);
            }
            if (uMipDstSize >= 0) {
                GL20C.glUniform2i(uMipDstSize, dstW, dstH);
            }
            GL42C.glBindImageTexture(0, texture, level - 1, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32F);
            GL42C.glBindImageTexture(1, texture, level, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
            GL43C.glDispatchCompute((dstW + 7) / 8, (dstH + 7) / 8, 1);
            GL42C.glMemoryBarrier(GL42C.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            srcW = dstW;
            srcH = dstH;
        }

        GL20C.glUseProgram(prevProg);
        GL42C.glBindImageTexture(0, 0, 0, false, 0, GL15C.GL_READ_ONLY, GL30C.GL_R32F);
        GL42C.glBindImageTexture(1, 0, 0, false, 0, GL15C.GL_WRITE_ONLY, GL30C.GL_R32F);
        GL13C.glActiveTexture(prevActive);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, prevTex);

        ready = true;
        lastBuildMips = levels;
        lastBuildNs = System.nanoTime() - t0;
    }

    private void ensureTexture(int w, int h) {
        if (texture != 0 && width == w && height == h) {
            return;
        }
        if (texture != 0) {
            GL11C.glDeleteTextures(texture);
            texture = 0;
        }
        width = w;
        height = h;
        levels = 1 + (int) Math.floor(Math.log(Math.max(w, h)) / Math.log(2));
        levels = Math.min(Math.max(levels, 1), 12);

        texture = GL11C.glGenTextures();
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST_MIPMAP_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, levels - 1);

        int lw = w, lh = h;
        for (int level = 0; level < levels; level++) {
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, level, GL30C.GL_R32F, lw, lh, 0,
                    GL11C.GL_RED, GL11C.GL_FLOAT, (java.nio.ByteBuffer) null);
            lw = Math.max(1, lw / 2);
            lh = Math.max(1, lh / 2);
        }
        ready = false;
    }

    public void bindTexture(int unit) {
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
    }

    @Override
    public void close() {
        if (texture != 0) {
            GL11C.glDeleteTextures(texture);
            texture = 0;
        }
        copyProgram.close();
        mipProgram.close();
        ready = false;
    }
}
