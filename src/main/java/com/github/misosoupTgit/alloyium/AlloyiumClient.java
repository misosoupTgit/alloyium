package com.github.misosoupTgit.alloyium;

import com.github.misosoupTgit.alloyium.compat.IrisCheck;
import com.github.misosoupTgit.alloyium.embeddium.AlloyiumWorldBridge;
import com.github.misosoupTgit.alloyium.render.HiZPyramid;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

final class AlloyiumClient {
    private AlloyiumClient() {}

    static void init() {
        MinecraftForge.EVENT_BUS.register(AlloyiumClient.class);
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(AlloyiumClient::onConfigLoad);
        modBus.addListener(AlloyiumClient::onConfigReload);
    }

    private static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getModId().equals(Alloyium.MOD_ID)) {
            Alloyium.refreshEnabled();
        }
    }

    private static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getModId().equals(Alloyium.MOD_ID)) {
            Alloyium.refreshEnabled();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!IrisCheck.isModPresent() || !AlloyiumConfig.verboseLogging()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        String msg = AlloyiumConfig.irisFallback()
                ? "[Alloyium] irisFallback ON — shader pack uses Embeddium/Iris terrain."
                : "[Alloyium] Oculus/Iris: Alloyium solid path active (shadows stay on Iris).";
        mc.player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.AQUA), false);
    }

    @SubscribeEvent
    public static void onAfterSolids(RenderLevelStageEvent event) {
        if (!HiZPyramid.enabled() || IrisCheck.isShaderPackInUse()) {
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
            return;
        }
        if (!AlloyiumWorldBridge.active()) {
            return;
        }
        var terrain = AlloyiumWorldBridge.get();
        if (terrain == null) {
            return;
        }
        HiZPyramid hiz = terrain.hiz();
        if (hiz != null) {
            hiz.captureAfterSolids();
        }
    }
}
