package com.github.misosoupTgit.alloyium.mixin.embeddium;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.compat.IrisCheck;
import com.github.misosoupTgit.alloyium.embeddium.AlloyiumWorldBridge;
import com.github.misosoupTgit.alloyium.render.AlloyiumTerrainRenderer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercept solid terrain → Alloyium.
 * Pack ON / shadow → Embeddium+Iris (R7.1); translucent sorted always falls through.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void alloyium$init(RenderDevice device, ChunkVertexType vertexType, CallbackInfo ci) {
        Alloyium.refreshEnabled();
        if (Alloyium.IS_ENABLED) {
            AlloyiumWorldBridge.create(vertexType);
        } else {
            AlloyiumWorldBridge.destroy();
            Alloyium.logVerbose("Alloyium terrain override inactive — Embeddium default path");
        }
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void alloyium$delete(CommandList commandList, CallbackInfo ci) {
        AlloyiumWorldBridge.destroy();
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void alloyium$render(ChunkRenderMatrices matrices,
                                 CommandList commandList,
                                 ChunkRenderListIterable renderLists,
                                 TerrainRenderPass renderPass,
                                 CameraTransform camera,
                                 CallbackInfo ci) {
        if (!Alloyium.IS_ENABLED || !AlloyiumWorldBridge.active() || renderPass.isSorted()) {
            return;
        }
        if (IrisCheck.shouldSkipIntercept()) {
            return;
        }

        AlloyiumTerrainRenderer terrain = AlloyiumWorldBridge.get();
        if (terrain == null) {
            return;
        }

        boolean drew = terrain.tryRender(
                (ShaderChunkRendererAccessor) this,
                matrices,
                commandList,
                renderLists,
                renderPass,
                camera
        );
        if (drew) {
            ci.cancel();
        }
    }
}
