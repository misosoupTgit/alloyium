package com.github.misosoupTgit.alloyium.compat;

import com.github.misosoupTgit.alloyium.mixin.embeddium.ShaderChunkRendererAccessor;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexAttributeBinding;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.compat.sodium.impl.IrisChunkShaderBindingPoints;
import net.irisshaders.iris.compat.sodium.impl.shader_overrides.ShaderChunkRendererExt;
import net.irisshaders.iris.compat.sodium.impl.vertex_format.IrisChunkMeshAttributes;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;

/**
 * R7 Iris/Oculus bridge — only load this class when {@link IrisCheck#isModPresent()} is true.
 * Mirrors Iris {@code MixinRegionChunkRenderer} attribute layout + program override resolution.
 */
public final class IrisTerrainCompat {
    private IrisTerrainCompat() {}

    public static boolean useExtendedVertexFormat() {
        return WorldRenderingSettings.INSTANCE.shouldUseExtendedVertexFormat();
    }

    /**
     * After {@code begin()}, Iris may have cancelled Embeddium's begin and bound {@code override}
     * with {@code activeProgram == null}. Prefer override; else Embeddium activeProgram.
     */
    public static ChunkShaderInterface resolveInterface(ShaderChunkRendererAccessor shaders) {
        if (shaders instanceof ShaderChunkRendererExt ext) {
            GlProgram<?> override = ext.iris$getOverride();
            if (override != null) {
                Object iface = override.getInterface();
                if (iface instanceof ChunkShaderInterface chunkIface) {
                    return chunkIface;
                }
            }
        }
        GlProgram<ChunkShaderInterface> active = shaders.alloyium$getActiveProgram();
        return active != null ? active.getInterface() : null;
    }

    /** Iris {@code begin} already binds + {@code setupState}; only bind Embeddium program when needed. */
    public static void ensureProgramBound(ShaderChunkRendererAccessor shaders) {
        if (shaders instanceof ShaderChunkRendererExt ext && ext.iris$getOverride() != null) {
            return;
        }
        GlProgram<ChunkShaderInterface> active = shaders.alloyium$getActiveProgram();
        if (active != null) {
            active.bind();
        }
    }

    public static GlVertexAttributeBinding[] createExtendedBindings(ChunkVertexType vertexType) {
        var format = vertexType.getVertexFormat();
        return new GlVertexAttributeBinding[] {
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.ATTRIBUTE_POSITION_ID,
                        format.getAttribute(ChunkMeshAttribute.POSITION_MATERIAL_MESH)),
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.ATTRIBUTE_COLOR,
                        format.getAttribute(ChunkMeshAttribute.COLOR_SHADE)),
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.ATTRIBUTE_BLOCK_TEXTURE,
                        format.getAttribute(ChunkMeshAttribute.BLOCK_TEXTURE)),
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.ATTRIBUTE_LIGHT_TEXTURE,
                        format.getAttribute(ChunkMeshAttribute.LIGHT_TEXTURE)),
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.MID_BLOCK,
                        format.getAttribute(IrisChunkMeshAttributes.MID_BLOCK)),
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.BLOCK_ID,
                        format.getAttribute(IrisChunkMeshAttributes.BLOCK_ID)),
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.MID_TEX_COORD,
                        format.getAttribute(IrisChunkMeshAttributes.MID_TEX_COORD)),
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.TANGENT,
                        format.getAttribute(IrisChunkMeshAttributes.TANGENT)),
                new GlVertexAttributeBinding(IrisChunkShaderBindingPoints.NORMAL,
                        format.getAttribute(IrisChunkMeshAttributes.NORMAL)),
        };
    }

    public static GlVertexAttributeBinding[] createStandardBindings(ChunkVertexType vertexType) {
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
}
