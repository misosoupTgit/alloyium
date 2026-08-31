package com.github.misosoupTgit.alloyium.mixin.embeddium;

import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public interface ShaderChunkRendererAccessor {
    @Invoker("begin")
    void alloyium$begin(TerrainRenderPass pass);

    @Invoker("end")
    void alloyium$end(TerrainRenderPass pass);

    @Accessor("activeProgram")
    GlProgram<ChunkShaderInterface> alloyium$getActiveProgram();
}
