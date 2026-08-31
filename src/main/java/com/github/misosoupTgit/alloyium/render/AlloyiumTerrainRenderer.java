package com.github.misosoupTgit.alloyium.render;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.compat.IrisCheck;
import com.github.misosoupTgit.alloyium.compat.IrisTerrainCompat;
import com.github.misosoupTgit.alloyium.compute.ComputePipeline;
import com.github.misosoupTgit.alloyium.compute.CpuIndirectBuilder;
import com.github.misosoupTgit.alloyium.debug.CullDebug;
import com.github.misosoupTgit.alloyium.debug.PerfMetrics;
import com.github.misosoupTgit.alloyium.gl.GpuBuffer;
import com.github.misosoupTgit.alloyium.meshlet.MeshletFrameList;
import com.github.misosoupTgit.alloyium.meshlet.MeshletHeader;
import com.github.misosoupTgit.alloyium.mixin.embeddium.ShaderChunkRendererAccessor;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.LocalSectionIndex;
import me.jellysquid.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import org.joml.Matrix4f;
import org.lwjgl.opengl.ARBIndirectParameters;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL46C;

import java.nio.ByteBuffer;
import java.util.Iterator;

/**
 * R6.1 + R7.2: region cache + CPU Indirect; Iris pack path keeps Alloyium and cuts GL/CPU tax.
 * Hi-Z remains opt-in ({@code -Dalloyium.hiz=true}).
 */
public final class AlloyiumTerrainRenderer implements AutoCloseable {
    private static final int VERTEX_BINDING = 0;

    private final ComputePipeline pipeline = new ComputePipeline();
    private final RegionCommandCache regionCache = new RegionCommandCache();
    private HiZPyramid hiz;
    private final MeshletFrameList meshlets = new MeshletFrameList();
    private final float[] frustumPlanes = new float[24];
    private final SharedQuadIndexBuffer sharedIndexBuffer;
    private final ChunkVertexType vertexType;
    private GlVertexAttributeBinding[] bindings;
    private boolean extendedBindings;
    private int vertexStride = 20;
    private ByteBuffer commandStaging;
    private int vao;
    private boolean vaoFormatReady;
    private int lastBoundVbo = -1;
    private int lastBoundIbo = -1;
    private boolean irisPackPass;

    public AlloyiumTerrainRenderer(ChunkVertexType vertexType) {
        this.vertexType = vertexType;
        CommandList cmd = RenderDevice.INSTANCE.createCommandList();
        this.sharedIndexBuffer = new SharedQuadIndexBuffer(cmd, SharedQuadIndexBuffer.IndexType.INTEGER);
        cmd.flush();
        this.bindings = createStandardBindings(vertexType);
        this.extendedBindings = false;
        this.vertexStride = bindings.length > 0 ? bindings[0].getStride() : 20;
        this.vao = GL30C.glGenVertexArrays();
        Alloyium.logVerbose(
                "AlloyiumTerrainRenderer R7.2 (regionCache={} cpuCull={} hiz={} irisMod={})",
                RegionCommandCache.enabled(), CpuIndirectBuilder.cpuCull(), HiZPyramid.enabled(),
                IrisCheck.isModPresent()
        );
    }

    public HiZPyramid hiz() {
        if (!HiZPyramid.enabled()) {
            return null;
        }
        if (hiz == null) {
            hiz = new HiZPyramid();
        }
        return hiz;
    }

    /**
     * @return true if Alloyium drew the pass (caller must {@code ci.cancel()}); false → Embeddium path.
     */
    public boolean tryRender(ShaderChunkRendererAccessor shaders,
                             ChunkRenderMatrices matrices,
                             CommandList commandList,
                             ChunkRenderListIterable renderLists,
                             TerrainRenderPass renderPass,
                             CameraTransform camera) {
        shaders.alloyium$begin(renderPass);
        ChunkShaderInterface shader = resolveShaderInterface(shaders);
        if (shader == null) {
            shaders.alloyium$end(renderPass);
            return false;
        }
        try {
            ensureBindings();
            renderPassBody(shaders, shader, matrices, commandList, renderLists, renderPass, camera);
            return true;
        } finally {
            shaders.alloyium$end(renderPass);
        }
    }

    private static ChunkShaderInterface resolveShaderInterface(ShaderChunkRendererAccessor shaders) {
        if (IrisCheck.isModPresent()) {
            return IrisTerrainCompat.resolveInterface(shaders);
        }
        var active = shaders.alloyium$getActiveProgram();
        return active != null ? active.getInterface() : null;
    }

    private void ensureBindings() {
        boolean wantExt = IrisCheck.isModPresent() && IrisTerrainCompat.useExtendedVertexFormat();
        if (bindings != null && wantExt == extendedBindings) {
            return;
        }
        if (wantExt) {
            bindings = IrisTerrainCompat.createExtendedBindings(vertexType);
        } else if (IrisCheck.isModPresent()) {
            bindings = IrisTerrainCompat.createStandardBindings(vertexType);
        } else {
            bindings = createStandardBindings(vertexType);
        }
        extendedBindings = wantExt;
        vertexStride = bindings.length > 0 ? bindings[0].getStride() : 20;
        vaoFormatReady = false;
    }

    private void renderPassBody(ShaderChunkRendererAccessor shaders,
                                ChunkShaderInterface shader,
                                ChunkRenderMatrices matrices,
                                CommandList commandList,
                                ChunkRenderListIterable renderLists,
                                TerrainRenderPass renderPass,
                                CameraTransform camera) {
        irisPackPass = IrisCheck.isShaderPackInUse();
        shader.setProjectionMatrix(matrices.projection());
        shader.setModelViewMatrix(matrices.modelView());

        boolean needCullPrep = CpuIndirectBuilder.cpuCull() || HiZPyramid.enabled();
        Matrix4f clip = null;
        float vpW = 0f;
        float vpH = 0f;
        if (needCullPrep) {
            clip = new Matrix4f(matrices.projection()).mul(matrices.modelView());
            FrustumUtil.extractPlanes(clip, frustumPlanes);
            vpW = net.minecraft.client.Minecraft.getInstance().getWindow().getWidth();
            vpH = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
        }

        float camX = camera.intX + camera.fracX;
        float camY = camera.intY + camera.fracY;
        float camZ = camera.intZ + camera.fracZ;
        HiZPyramid hizPyramid = hiz();
        if (hizPyramid != null && !irisPackPass) {
            hizPyramid.noteCamera(camX, camY, camZ);
        }

        int passHash = System.identityHashCode(renderPass);
        // Shadow is not intercepted (IrisCheck.shouldSkipIntercept); keep flag for safety.
        boolean shadowPass = false;

        CullDebug.beginSolidPass();
        PerfMetrics.beginSolidPass();

        if (IrisCheck.isModPresent()) {
            IrisTerrainCompat.ensureProgramBound(shaders);
        } else {
            shaders.alloyium$getActiveProgram().bind();
        }
        int passMaxElements = 0;
        boolean vaoBound = false;
        lastBoundVbo = -1;
        lastBoundIbo = -1;

        Iterator<ChunkRenderList> iterator = renderLists.iterator(renderPass.isReverseOrder());
        while (iterator.hasNext()) {
            ChunkRenderList renderList = iterator.next();
            RenderRegion region = renderList.getRegion();
            SectionRenderDataStorage storage = region.getStorage(renderPass);
            if (storage == null) {
                continue;
            }
            RenderRegion.DeviceResources resources = region.getResources();
            if (resources == null) {
                continue;
            }
            int glVbo = resources.getVertexBuffer().handle();

            long key = RegionCommandCache.regionKey(
                    region.getOriginX(), region.getOriginY(), region.getOriginZ(), passHash);
            long listSig = listSignature(renderList, renderPass, irisPackPass);

            if (RegionCommandCache.enabled()) {
                RegionCommandCache.Entry cached = regionCache.get(key);
                if (cached != null && cached.epochHit(glVbo, camera.intX, camera.intY, camera.intZ, listSig)) {
                    if (cached.maxElements > passMaxElements) {
                        sharedIndexBuffer.ensureCapacity(commandList, cached.maxElements);
                        passMaxElements = cached.maxElements;
                    }
                    if (!vaoBound) {
                        beginPassVao();
                        vaoBound = true;
                    }
                    PerfMetrics.recordRegion(cached.meshletIn, cached.drawCount, true, false, true, true);
                    setRegionOffset(shader, region, camera);
                    drawIndirect(glVbo, cached.commands, cached.drawCount, false);
                    continue;
                }
                if (cached != null && cached.drawCount > 0 && cached.glVbo == glVbo) {
                    long fp = fingerprint(region, storage, renderList, renderPass, camera, glVbo, shadowPass);
                    if (cached.fingerprint == fp) {
                        cached.epoch = RegionCacheEpoch.current();
                        cached.camX = camera.intX;
                        cached.camY = camera.intY;
                        cached.camZ = camera.intZ;
                        cached.listSig = listSig;
                        if (cached.maxElements > passMaxElements) {
                            sharedIndexBuffer.ensureCapacity(commandList, cached.maxElements);
                            passMaxElements = cached.maxElements;
                        }
                        if (!vaoBound) {
                            beginPassVao();
                            vaoBound = true;
                        }
                        PerfMetrics.recordRegion(cached.meshletIn, cached.drawCount, true, false, true, false);
                        setRegionOffset(shader, region, camera);
                        drawIndirect(glVbo, cached.commands, cached.drawCount, false);
                        continue;
                    }
                }
            }

            meshlets.clear();
            long[] fpOut = new long[1];
            int maxElements = collectRegionMeshlets(region, storage, renderList, renderPass, camera, glVbo,
                    fpOut, shadowPass);
            if (meshlets.size() == 0 || maxElements <= 0) {
                continue;
            }
            if (maxElements > passMaxElements) {
                sharedIndexBuffer.ensureCapacity(commandList, maxElements);
                passMaxElements = maxElements;
            }
            long fp = fpOut[0];

            boolean useGpu = !irisPackPass && (meshlets.size() > ComputePipeline.cpuCmdThreshold()
                    || (hizPyramid != null && hizPyramid.isReady()
                    && com.github.misosoupTgit.alloyium.AlloyiumConfig.hizForceGpu()));
            if (!useGpu) {
                commandStaging = CpuIndirectBuilder.ensureCapacity(commandStaging, meshlets.size());
                int draws = CpuIndirectBuilder.build(meshlets, frustumPlanes, camX, camY, camZ, commandStaging);
                if (draws <= 0) {
                    continue;
                }
                GpuBuffer cmdBuf;
                if (RegionCommandCache.enabled()) {
                    RegionCommandCache.Entry entry = regionCache.getOrCreate(key);
                    entry.upload(commandStaging, draws, meshlets.size(), maxElements, glVbo, fp, listSig,
                            camera.intX, camera.intY, camera.intZ);
                    cmdBuf = entry.commands;
                } else {
                    pipeline.commandsBuffer().ensureCapacity((long) draws * ComputePipeline.COMMAND_BYTES,
                            GL15C.GL_DYNAMIC_DRAW);
                    pipeline.commandsBuffer().uploadSub(0, commandStaging);
                    cmdBuf = pipeline.commandsBuffer();
                }
                if (!vaoBound) {
                    beginPassVao();
                    vaoBound = true;
                }
                PerfMetrics.recordRegion(meshlets.size(), draws, true, false, false, false);
                setRegionOffset(shader, region, camera);
                drawIndirect(glVbo, cmdBuf, draws, false);
                continue;
            }

            if (clip == null) {
                clip = new Matrix4f(matrices.projection()).mul(matrices.modelView());
                FrustumUtil.extractPlanes(clip, frustumPlanes);
                vpW = net.minecraft.client.Minecraft.getInstance().getWindow().getWidth();
                vpH = net.minecraft.client.Minecraft.getInstance().getWindow().getHeight();
            }
            ComputePipeline.DrawPrep prep = pipeline.prepare(
                    meshlets, frustumPlanes, camX, camY, camZ, clip, vpW, vpH, hizPyramid);
            if (CullDebug.logEnabled()) {
                CullDebug.CpuCullResult cpu = CullDebug.mirrorCpu(meshlets, frustumPlanes, camX, camY, camZ);
                CullDebug.recordRegion(cpu, prep.debugVisible(), prep.debugFrustum(), prep.debugCone(),
                        prep.debugOcclusion(), frustumPlanes, camX, camY, camZ);
            }
            if (prep.drawCount() <= 0) {
                continue;
            }
            if (!vaoBound) {
                beginPassVao();
                vaoBound = true;
            }
            PerfMetrics.recordRegion(prep.meshletIn(), prep.drawCount(), prep.cpuPath(), prep.indirectCount(), false, false);
            setRegionOffset(shader, region, camera);
            drawIndirect(glVbo, pipeline.commandsBuffer(), prep.drawCount(), prep.indirectCount());
        }

        if (vaoBound) {
            endPassVao();
        }

        CullDebug.endSolidPass();
        PerfMetrics.endSolidPass(hizPyramid);
    }

    /** Pack-on: cheaper mix than full FNV (still order-sensitive). */
    private static long listSignature(ChunkRenderList renderList, TerrainRenderPass pass, boolean cheap) {
        var sectionIterator = renderList.sectionsWithGeometryIterator(pass.isReverseOrder());
        if (sectionIterator == null) {
            return 0xcbf29ce484222325L;
        }
        if (cheap) {
            long h = 0x9E3779B97F4A7C15L;
            int n = 0;
            while (sectionIterator.hasNext()) {
                int s = sectionIterator.nextByteAsInt();
                h ^= (s + 0x9e3779b9L) * (n + 1L);
                n++;
            }
            return h ^ ((long) n << 32);
        }
        long h = 0xcbf29ce484222325L;
        while (sectionIterator.hasNext()) {
            h = RegionCommandCache.fnv(h, sectionIterator.nextByteAsInt());
        }
        return h;
    }

    private long fingerprint(RenderRegion region,
                             SectionRenderDataStorage storage,
                             ChunkRenderList renderList,
                             TerrainRenderPass pass,
                             CameraTransform camera,
                             int glVbo,
                             boolean shadowPass) {
        long h = 0xcbf29ce484222325L;
        h = RegionCommandCache.fnv(h, camera.intX);
        h = RegionCommandCache.fnv(h, camera.intY);
        h = RegionCommandCache.fnv(h, camera.intZ);
        h = RegionCommandCache.fnv(h, glVbo);
        h = RegionCommandCache.fnv(h, shadowPass ? 1 : 0);
        var sectionIterator = renderList.sectionsWithGeometryIterator(pass.isReverseOrder());
        if (sectionIterator == null) {
            return h;
        }
        int originX = region.getChunkX();
        int originY = region.getChunkY();
        int originZ = region.getChunkZ();
        while (sectionIterator.hasNext()) {
            int sectionIndex = sectionIterator.nextByteAsInt();
            long pMeshData = storage.getDataPointer(sectionIndex);
            int chunkX = originX + LocalSectionIndex.unpackX(sectionIndex);
            int chunkY = originY + LocalSectionIndex.unpackY(sectionIndex);
            int chunkZ = originZ + LocalSectionIndex.unpackZ(sectionIndex);
            int slices = SectionRenderDataUnsafe.getSliceMask(pMeshData);
            if (!shadowPass) {
                slices &= visibleFaces(camera.intX, camera.intY, camera.intZ, chunkX, chunkY, chunkZ);
            }
            h = RegionCommandCache.fnv(h, sectionIndex);
            h = RegionCommandCache.fnv(h, slices);
            if (slices == 0) {
                continue;
            }
            for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
                if (((slices >> facing) & 1) == 0) {
                    continue;
                }
                h = RegionCommandCache.fnv(h, SectionRenderDataUnsafe.getElementCount(pMeshData, facing));
                h = RegionCommandCache.fnv(h, SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing));
            }
        }
        return h;
    }

    private int collectRegionMeshlets(RenderRegion region,
                                      SectionRenderDataStorage storage,
                                      ChunkRenderList renderList,
                                      TerrainRenderPass pass,
                                      CameraTransform camera,
                                      int glVbo,
                                      long[] fpOut,
                                      boolean shadowPass) {
        var sectionIterator = renderList.sectionsWithGeometryIterator(pass.isReverseOrder());
        long h = 0xcbf29ce484222325L;
        h = RegionCommandCache.fnv(h, camera.intX);
        h = RegionCommandCache.fnv(h, camera.intY);
        h = RegionCommandCache.fnv(h, camera.intZ);
        h = RegionCommandCache.fnv(h, glVbo);
        h = RegionCommandCache.fnv(h, shadowPass ? 1 : 0);
        if (sectionIterator == null || region.getResources() == null) {
            fpOut[0] = h;
            return 0;
        }

        int originX = region.getChunkX();
        int originY = region.getChunkY();
        int originZ = region.getChunkZ();
        int maxElements = 0;

        while (sectionIterator.hasNext()) {
            int sectionIndex = sectionIterator.nextByteAsInt();
            int chunkX = originX + LocalSectionIndex.unpackX(sectionIndex);
            int chunkY = originY + LocalSectionIndex.unpackY(sectionIndex);
            int chunkZ = originZ + LocalSectionIndex.unpackZ(sectionIndex);
            long pMeshData = storage.getDataPointer(sectionIndex);
            int slices = SectionRenderDataUnsafe.getSliceMask(pMeshData);
            if (!shadowPass) {
                slices &= visibleFaces(camera.intX, camera.intY, camera.intZ, chunkX, chunkY, chunkZ);
            }
            h = RegionCommandCache.fnv(h, sectionIndex);
            h = RegionCommandCache.fnv(h, slices);
            if (slices == 0) {
                continue;
            }
            float cx = (chunkX << 4) + 8.0f;
            float cy = (chunkY << 4) + 8.0f;
            float cz = (chunkZ << 4) + 8.0f;
            for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
                if (((slices >> facing) & 1) == 0) {
                    continue;
                }
                int elementCount = SectionRenderDataUnsafe.getElementCount(pMeshData, facing);
                if (elementCount <= 0) {
                    continue;
                }
                int vertexOffset = SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing);
                h = RegionCommandCache.fnv(h, elementCount);
                h = RegionCommandCache.fnv(h, vertexOffset);

                MeshletHeader hdr = meshlets.acquire();
                hdr.vertexOffset = vertexOffset;
                hdr.vertexCount = elementCount;
                hdr.primitiveOffset = 0;
                hdr.primitiveCount = elementCount / 3;
                hdr.sphereX = cx;
                hdr.sphereY = cy;
                hdr.sphereZ = cz;
                hdr.sphereRadius = 14.0f;
                hdr.coneW = 2.0f;
                maxElements = Math.max(maxElements, elementCount);
            }
        }
        fpOut[0] = h;
        return maxElements;
    }

    private static int visibleFaces(int originX, int originY, int originZ, int chunkX, int chunkY, int chunkZ) {
        int boundsMinX = (chunkX << 4), boundsMaxX = boundsMinX + 16;
        int boundsMinY = (chunkY << 4), boundsMaxY = boundsMinY + 16;
        int boundsMinZ = (chunkZ << 4), boundsMaxZ = boundsMinZ + 16;
        int planes = (1 << ModelQuadFacing.UNASSIGNED.ordinal());
        planes |= greaterThan(originX, boundsMinX - 3) << ModelQuadFacing.POS_X.ordinal();
        planes |= greaterThan(originY, boundsMinY - 3) << ModelQuadFacing.POS_Y.ordinal();
        planes |= greaterThan(originZ, boundsMinZ - 3) << ModelQuadFacing.POS_Z.ordinal();
        planes |= lessThan(originX, boundsMaxX + 3) << ModelQuadFacing.NEG_X.ordinal();
        planes |= lessThan(originY, boundsMaxY + 3) << ModelQuadFacing.NEG_Y.ordinal();
        planes |= lessThan(originZ, boundsMaxZ + 3) << ModelQuadFacing.NEG_Z.ordinal();
        return planes;
    }

    private static int greaterThan(int a, int b) {
        return (b - a) >>> 31;
    }

    private static int lessThan(int a, int b) {
        return (a - b) >>> 31;
    }

    private void setRegionOffset(ChunkShaderInterface shader, RenderRegion region, CameraTransform camera) {
        float x = (region.getOriginX() - camera.intX) - camera.fracX;
        float y = (region.getOriginY() - camera.intY) - camera.fracY;
        float z = (region.getOriginZ() - camera.intZ) - camera.fracZ;
        shader.setRegionOffset(x, y, z);
    }

    private void beginPassVao() {
        GL30C.glBindVertexArray(vao);
        if (irisPackPass && !vaoFormatReady) {
            setupVaoFormatOnce();
            vaoFormatReady = true;
        }
    }

    private void setupVaoFormatOnce() {
        for (GlVertexAttributeBinding binding : bindings) {
            int index = binding.getIndex();
            GL20C.glEnableVertexAttribArray(index);
            if (binding.isIntType()) {
                GL43C.glVertexAttribIFormat(index, binding.getCount(), binding.getFormat(),
                        (int) binding.getPointer());
            } else {
                GL43C.glVertexAttribFormat(index, binding.getCount(), binding.getFormat(),
                        binding.isNormalized(), (int) binding.getPointer());
            }
            GL43C.glVertexAttribBinding(index, VERTEX_BINDING);
        }
    }

    private void endPassVao() {
        GL15C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, 0);
        GL30C.glBindVertexArray(0);
        lastBoundVbo = -1;
        lastBoundIbo = -1;
    }

    private void drawIndirect(int glVbo, GpuBuffer commands, int drawCount, boolean indirectCount) {
        int glIbo = sharedIndexBuffer.getBufferObject().handle();
        if (irisPackPass) {
            // XHFP has many attrs — format once, swap VBO only (Iris-path tax cut).
            if (glVbo != lastBoundVbo) {
                GL43C.glBindVertexBuffer(VERTEX_BINDING, glVbo, 0L, vertexStride);
                lastBoundVbo = glVbo;
            }
            if (glIbo != lastBoundIbo) {
                GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, glIbo);
                lastBoundIbo = glIbo;
            }
        } else {
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, glVbo);
            enableAttributes();
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, glIbo);
        }
        commands.bindDrawIndirect();
        if (indirectCount) {
            pipeline.countersBuffer().bindParameterBuffer();
            if (org.lwjgl.opengl.GL.getCapabilities().OpenGL46) {
                GL46C.glMultiDrawElementsIndirectCount(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L, 0L, drawCount, 0);
            } else {
                ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(
                        GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L, 0L, drawCount, 0);
            }
            GL15C.glBindBuffer(ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB, 0);
        } else {
            GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L, drawCount, 0);
        }
    }

    private void enableAttributes() {
        for (GlVertexAttributeBinding binding : bindings) {
            int index = binding.getIndex();
            GL20C.glEnableVertexAttribArray(index);
            if (binding.isIntType()) {
                GL30C.glVertexAttribIPointer(index, binding.getCount(), binding.getFormat(),
                        binding.getStride(), binding.getPointer());
            } else {
                GL20C.glVertexAttribPointer(index, binding.getCount(), binding.getFormat(),
                        binding.isNormalized(), binding.getStride(), binding.getPointer());
            }
        }
    }

    private static GlVertexAttributeBinding[] createStandardBindings(ChunkVertexType vertexType) {
        var format = vertexType.getVertexFormat();
        return new GlVertexAttributeBinding[] {
                new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID,
                        format.getAttribute(ChunkMeshAttribute.POSITION_MATERIAL_MESH)),
                new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                        format.getAttribute(ChunkMeshAttribute.COLOR_SHADE)),
                new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE,
                        format.getAttribute(ChunkMeshAttribute.BLOCK_TEXTURE)),
                new GlVertexAttributeBinding(ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE,
                        format.getAttribute(ChunkMeshAttribute.LIGHT_TEXTURE))
        };
    }

    @Override
    public void close() {
        pipeline.close();
        regionCache.close();
        if (hiz != null) {
            hiz.close();
            hiz = null;
        }
        if (vao != 0) {
            GL30C.glDeleteVertexArrays(vao);
            vao = 0;
        }
        vaoFormatReady = false;
        CommandList cmd = RenderDevice.INSTANCE.createCommandList();
        sharedIndexBuffer.delete(cmd);
        cmd.flush();
    }
}
