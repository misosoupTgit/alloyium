package com.github.misosoupTgit.alloyium;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Alloyium — Compute B' terrain path for Embeddium (client rendering only).
 * Constitution: never write vertex attributes from Compute; only Indirect commands.
 */
@Mod(Alloyium.MOD_ID)
public class Alloyium {
    public static final String MOD_ID = "alloyium";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** GL 4.3 + required extensions detected after context creation. */
    public static boolean IS_COMPATIBLE = false;
    /** GL 4.6 or ARB_indirect_parameters — MultiDrawElementsIndirectCount. */
    public static boolean HAS_INDIRECT_COUNT = false;
    /** Runtime enable gate (compatible && !forceDisable). */
    public static boolean IS_ENABLED = false;

    public Alloyium() {
        LOGGER.info("Alloyium {} — client config + R7 Iris bridge", "1.0.2");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, AlloyiumConfig.SPEC);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            AlloyiumClient.init();
        }
    }

    public static void refreshEnabled() {
        IS_ENABLED = IS_COMPATIBLE && !AlloyiumConfig.forceDisable();
    }

    /** Non-essential info logs (gated by {@code general.verboseLogging}). */
    public static void logVerbose(String msg, Object... args) {
        if (AlloyiumConfig.verboseLogging()) {
            LOGGER.info(msg, args);
        }
    }
}
