package com.github.misosoupTgit.alloyium.compat;

import com.github.misosoupTgit.alloyium.Alloyium;
import com.github.misosoupTgit.alloyium.AlloyiumConfig;
import net.minecraftforge.fml.ModList;

/**
 * R7.2: Alloyium stays active under shader packs; cut Iris-path taxes instead of bailing out.
 * <ul>
 *   <li>Pack ON → Alloyium Indirect + Iris program/XHFP (default)</li>
 *   <li>Shadow → Embeddium/Iris only (Iris face-cull redirect must run)</li>
 *   <li>Escape: config {@code oculus.irisFallback} → Embeddium while pack active</li>
 * </ul>
 */
public final class IrisCheck {
    private static Boolean modPresent;
    private static boolean loggedPresent;

    private IrisCheck() {}

    public static boolean isModPresent() {
        if (modPresent == null) {
            modPresent = ModList.get().isLoaded("oculus") || ModList.get().isLoaded("iris");
            if (modPresent && !loggedPresent) {
                loggedPresent = true;
                Alloyium.LOGGER.warn(
                        "Oculus/Iris is installed. Alloyium still accelerates terrain, "
                                + "but several features are limited or inactive for shader compatibility. "
                                + "If you do not depend on Oculus and are not planning to use shader packs, "
                                + "removing Oculus is recommended for better performance."
                );
                Alloyium.logVerbose(
                        "Alloyium Iris bridge: shadows stay on Iris; irisFallback in config/alloyium-client.toml"
                );
            }
        }
        return modPresent;
    }

    /** @deprecated no longer hard-disables for mod presence. */
    @Deprecated
    public static boolean shouldDisableAlloyium() {
        return false;
    }

    public static boolean isShaderPackInUse() {
        if (!isModPresent()) {
            return false;
        }
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(inst));
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRenderingShadowPass() {
        if (!isModPresent()) {
            return false;
        }
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isRenderingShadowPass").invoke(inst));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Escape: config {@code oculus.irisFallback=true} → Embeddium while pack active. */
    public static boolean shouldFallbackToEmbeddium() {
        return AlloyiumConfig.irisFallback() && isShaderPackInUse();
    }

    /** Skip Alloyium intercept for this call. */
    public static boolean shouldSkipIntercept() {
        if (shouldFallbackToEmbeddium()) {
            return true;
        }
        // Shadows: Iris MixinDefaultChunkRenderer face-cull redirect + shadow program.
        return isRenderingShadowPass();
    }

    public static void invalidate() {
        modPresent = null;
    }
}
