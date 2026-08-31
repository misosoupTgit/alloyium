package com.github.misosoupTgit.alloyium.mixin.embeddium;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.debug.CullDebug;
import com.github.misosoupTgit.alloyium.debug.PerfMetrics;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Inject(method = "getDebugStrings", at = @At("RETURN"), cancellable = true)
    private void alloyium$debug(CallbackInfoReturnable<Collection<String>> cir) {
        if (!Alloyium.IS_ENABLED) {
            return;
        }
        ArrayList<String> lines = new ArrayList<>(cir.getReturnValue());
        lines.add(CullDebug.f3Line0);
        if (!PerfMetrics.f3Line.isEmpty()) {
            lines.add(PerfMetrics.f3Line);
        }
        if (!CullDebug.f3Line1.isEmpty()) {
            lines.add(CullDebug.f3Line1);
        }
        if (!CullDebug.f3Line2.isEmpty()) {
            lines.add(CullDebug.f3Line2);
        }
        cir.setReturnValue(lines);
    }
}
