package com.github.misosoupTgit.alloyium.compute;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.debug.CullDebug;
import com.github.misosoupTgit.alloyium.gl.GpuBuffer;
import com.github.misosoupTgit.alloyium.gl.IndirectParametersPolicy;
import com.github.misosoupTgit.alloyium.gl.ShaderProgram;
import com.github.misosoupTgit.alloyium.meshlet.MeshletFrameList;
import com.github.misosoupTgit.alloyium.meshlet.MeshletHeader;
import com.github.misosoupTgit.alloyium.render.HiZPyramid;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL42C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * R4/R5: CPU fast-path or Compute A+B' (+ Hi-Z) → Indirect cmds. Never writes vertex attributes.
 */
public final class ComputePipeline implements AutoCloseable {
    public static final int MAX_MESHLETS = 65536;
    public static final int COMMAND_BYTES = 20;
    public static final int CPU_CMD_THRESHOLD_DEFAULT = 768;

    public static int cpuCmdThreshold() {
        return com.github.misosoupTgit.alloyium.AlloyiumConfig.cpuCmdThreshold();
    }
    private static final int HIZ_TEXTURE_UNIT = 4;

    private final ShaderProgram computeAb;
    private final GpuBuffer meshletSsbo = new GpuBuffer();
    private final GpuBuffer visibleIdsSsbo = new GpuBuffer();
    private final GpuBuffer countersSsbo = new GpuBuffer();
    private final GpuBuffer commandsSsbo = new GpuBuffer();

    private final int uFrustumPlanes;
    private final int uCameraPos;
    private final int uMeshletCount;
    private final int uEnableFrustum;
    private final int uEnableCone;
    private final int uEnableHiz;
    private final int uHizBias;
    private final int uViewProj;
    private final int uViewport;
    private final int uHizMaxLevel;
    private final int uHiZSampler;

    private ByteBuffer meshletStaging;
    private ByteBuffer commandStaging;

    public ComputePipeline() {
        this.computeAb = ShaderProgram.compileCompute("/assets/alloyium/shaders/compute_ab_prime.glsl");
        this.uFrustumPlanes = computeAb.uniformLocation("uFrustumPlanes");
        this.uCameraPos = computeAb.uniformLocation("uCameraPos");
        this.uMeshletCount = computeAb.uniformLocation("uMeshletCount");
        this.uEnableFrustum = computeAb.uniformLocation("uEnableFrustum");
        this.uEnableCone = computeAb.uniformLocation("uEnableCone");
        this.uEnableHiz = computeAb.uniformLocation("uEnableHiz");
        this.uHizBias = computeAb.uniformLocation("uHizBias");
        this.uViewProj = computeAb.uniformLocation("uViewProj");
        this.uViewport = computeAb.uniformLocation("uViewport");
        this.uHizMaxLevel = computeAb.uniformLocation("uHizMaxLevel");
        this.uHiZSampler = computeAb.uniformLocation("uHiZ");

        meshletSsbo.allocate((long) MAX_MESHLETS * MeshletHeader.BYTES, GL15C.GL_DYNAMIC_DRAW);
        visibleIdsSsbo.allocate((long) MAX_MESHLETS * Integer.BYTES, GL15C.GL_DYNAMIC_DRAW);
        countersSsbo.allocate(16, GL15C.GL_DYNAMIC_DRAW);
        commandsSsbo.allocate((long) MAX_MESHLETS * COMMAND_BYTES, GL15C.GL_DYNAMIC_DRAW);

        Alloyium.logVerbose(
                "ComputePipeline R6 ready (cpuThreshold={} CountARB={} cpuCull={} H={})",
                cpuCmdThreshold(), IndirectParametersPolicy.useIndirectCount(),
                CpuIndirectBuilder.cpuCull(), HiZPyramid.enabled()
        );
    }

    public record DrawPrep(int drawCount, int meshletIn, boolean cpuPath, boolean indirectCount,
                           int debugVisible, int debugFrustum, int debugCone, int debugOcclusion) {}

    public DrawPrep prepare(MeshletFrameList list, float[] frustumPlanes6x4,
                            float camX, float camY, float camZ,
                            Matrix4f viewProj, float viewportW, float viewportH,
                            HiZPyramid hiz) {
        int count = Math.min(list.size(), MAX_MESHLETS);
        if (count == 0) {
            return new DrawPrep(0, 0, true, false, 0, 0, 0, 0);
        }

        // Keep R3 CPU fast-path for small N. Hi-Z only applies on GPU path (large batches).
        if (count <= cpuCmdThreshold()) {
            return prepareCpu(list, frustumPlanes6x4, camX, camY, camZ, count);
        }
        return prepareGpu(list, frustumPlanes6x4, camX, camY, camZ, count, viewProj, viewportW, viewportH, hiz);
    }

    private DrawPrep prepareCpu(MeshletFrameList list, float[] planes, float camX, float camY, float camZ, int count) {
        commandStaging = CpuIndirectBuilder.ensureCapacity(commandStaging, count);
        int visible = CpuIndirectBuilder.build(list, planes, camX, camY, camZ, commandStaging);
        if (visible <= 0) {
            return new DrawPrep(0, count, true, false, 0, 0, 0, 0);
        }
        commandsSsbo.ensureCapacity((long) visible * COMMAND_BYTES, GL15C.GL_DYNAMIC_DRAW);
        commandsSsbo.uploadSub(0, commandStaging);
        return new DrawPrep(visible, count, true, false, visible, 0, 0, 0);
    }

    private DrawPrep prepareGpu(MeshletFrameList list, float[] frustumPlanes6x4,
                                float camX, float camY, float camZ, int count,
                                Matrix4f viewProj, float viewportW, float viewportH,
                                HiZPyramid hiz) {
        int bytes = count * MeshletHeader.BYTES;
        meshletStaging = ensureDirect(meshletStaging, bytes);
        meshletStaging.clear();
        for (int i = 0; i < count; i++) {
            list.headers().get(i).write(meshletStaging);
        }
        meshletStaging.flip();
        meshletSsbo.ensureCapacity((long) bytes, GL15C.GL_DYNAMIC_DRAW);
        meshletSsbo.uploadSub(0, meshletStaging);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            countersSsbo.uploadSub(0, stack.ints(0, 0, 0, 0));
        }

        boolean useCount = IndirectParametersPolicy.useIndirectCount();
        if (!useCount) {
            commandsSsbo.zeroRange(0, (long) count * COMMAND_BYTES);
        }

        int previousProgram = GL20C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int prevActive = org.lwjgl.opengl.GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int prevTex = org.lwjgl.opengl.GL11C.glGetInteger(org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D);

        boolean hizOn = hiz != null && hiz.isReady();
        computeAb.use();
        if (uMeshletCount >= 0) {
            GL30C.glUniform1ui(uMeshletCount, count);
        }
        if (uCameraPos >= 0) {
            GL20C.glUniform3f(uCameraPos, camX, camY, camZ);
        }
        if (uEnableFrustum >= 0) {
            GL30C.glUniform1ui(uEnableFrustum, CullDebug.enableFrustum() ? 1 : 0);
        }
        if (uEnableCone >= 0) {
            GL30C.glUniform1ui(uEnableCone, CullDebug.enableCone() ? 1 : 0);
        }
        if (uEnableHiz >= 0) {
            GL30C.glUniform1ui(uEnableHiz, hizOn ? 1 : 0);
        }
        if (uHizBias >= 0) {
            GL20C.glUniform1f(uHizBias, HiZPyramid.bias());
        }
        if (uViewport >= 0) {
            GL20C.glUniform2f(uViewport, viewportW, viewportH);
        }
        if (uHizMaxLevel >= 0) {
            GL20C.glUniform1i(uHizMaxLevel, hizOn ? hiz.maxLevel() : 0);
        }
        if (uViewProj >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer fb = stack.mallocFloat(16);
                viewProj.get(fb);
                GL20C.glUniformMatrix4fv(uViewProj, false, fb);
            }
        }
        if (uFrustumPlanes >= 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer fb = stack.mallocFloat(24);
                fb.put(frustumPlanes6x4).flip();
                GL20C.glUniform4fv(uFrustumPlanes, fb);
            }
        }

        if (hizOn) {
            hiz.bindTexture(HIZ_TEXTURE_UNIT);
            if (uHiZSampler >= 0) {
                GL20C.glUniform1i(uHiZSampler, HIZ_TEXTURE_UNIT);
            }
        }

        meshletSsbo.bindSsbo(0);
        visibleIdsSsbo.bindSsbo(1);
        countersSsbo.bindSsbo(2);
        commandsSsbo.bindSsbo(3);

        int groups = (count + 63) / 64;
        GL43C.glDispatchCompute(groups, 1, 1);
        GL42C.glMemoryBarrier(GL43C.GL_COMMAND_BARRIER_BIT | GL43C.GL_SHADER_STORAGE_BARRIER_BIT
                | GL42C.GL_TEXTURE_FETCH_BARRIER_BIT);

        int debugVis = 0, debugF = 0, debugC = 0, debugO = 0;
        if (CullDebug.logEnabled() && com.github.misosoupTgit.alloyium.AlloyiumConfig.verboseLogging()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer stats = stack.mallocInt(4);
                GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, countersSsbo.id());
                GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, stats);
                debugVis = stats.get(0);
                debugF = stats.get(1);
                debugC = stats.get(2);
                debugO = stats.get(3);
            }
        }

        GL20C.glUseProgram(previousProgram);
        GL13C.glActiveTexture(prevActive);
        org.lwjgl.opengl.GL11C.glBindTexture(org.lwjgl.opengl.GL11C.GL_TEXTURE_2D, prevTex);

        return new DrawPrep(count, count, false, useCount, debugVis, debugF, debugC, debugO);
    }

    public GpuBuffer commandsBuffer() {
        return commandsSsbo;
    }

    public GpuBuffer countersBuffer() {
        return countersSsbo;
    }

    private static ByteBuffer ensureDirect(ByteBuffer current, int bytes) {
        if (current != null && current.capacity() >= bytes) {
            return current;
        }
        int cap = current == null ? bytes : Math.max(bytes, current.capacity() * 2);
        return ByteBuffer.allocateDirect(cap).order(ByteOrder.nativeOrder());
    }

    @Override
    public void close() {
        computeAb.close();
        meshletSsbo.close();
        visibleIdsSsbo.close();
        countersSsbo.close();
        commandsSsbo.close();
    }
}
