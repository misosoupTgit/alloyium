package com.github.misosoupTgit.alloyium.mixin.minecraft;

import com.github.misosoupTgit.alloyium.gl.GlCapabilitiesCheck;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Window.class)
public class MixinWindow {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void alloyium$onWindowCreated(CallbackInfo ci) {
        GlCapabilitiesCheck.check();
    }
}
