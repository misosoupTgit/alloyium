package com.github.misosoupTgit.alloyium.mixin.embeddium;

import com.github.misosoupTgit.alloyium.render.RegionCacheEpoch;
import me.jellysquid.mods.sodium.client.gl.arena.GlBufferSegment;
import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SectionRenderDataStorage.class, remap = false)
public class MixinSectionRenderDataStorage {
    @Inject(method = "setMeshes", at = @At("TAIL"))
    private void alloyium$bumpOnSet(int sectionIndex, GlBufferSegment vertex, GlBufferSegment index,
                                    VertexRange[] ranges, CallbackInfo ci) {
        RegionCacheEpoch.bump();
    }

    @Inject(method = "removeMeshes", at = @At("HEAD"))
    private void alloyium$bumpOnRemove(int sectionIndex, CallbackInfo ci) {
        RegionCacheEpoch.bump();
    }

    @Inject(method = "onBufferResized", at = @At("HEAD"))
    private void alloyium$bumpOnResize(CallbackInfo ci) {
        RegionCacheEpoch.bump();
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void alloyium$bumpOnDelete(CallbackInfo ci) {
        RegionCacheEpoch.bump();
    }
}
