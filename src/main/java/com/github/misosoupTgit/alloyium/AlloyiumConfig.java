package com.github.misosoupTgit.alloyium;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client config ({@code config/alloyium-client.toml}).
 */
public final class AlloyiumConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue FORCE_DISABLE;
    public static final ForgeConfigSpec.BooleanValue REGION_CACHE;
    public static final ForgeConfigSpec.BooleanValue CPU_CULL;
    public static final ForgeConfigSpec.IntValue CPU_CMD_THRESHOLD;

    public static final ForgeConfigSpec.BooleanValue HIZ;
    public static final ForgeConfigSpec.BooleanValue HIZ_FORCE_GPU;
    public static final ForgeConfigSpec.DoubleValue HIZ_BIAS;
    public static final ForgeConfigSpec.IntValue HIZ_DOWNSAMPLE;
    public static final ForgeConfigSpec.IntValue HIZ_EVERY;

    public static final ForgeConfigSpec.BooleanValue IRIS_FALLBACK;

    public static final ForgeConfigSpec.BooleanValue DEBUG_CULL;
    public static final ForgeConfigSpec.BooleanValue CULL_FRUSTUM;
    public static final ForgeConfigSpec.BooleanValue CULL_CONE;

    /** Single switch for non-essential logs / chat / detailed F3. */
    public static final ForgeConfigSpec.BooleanValue VERBOSE_LOGGING;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        FORCE_DISABLE = b
                .comment("Disable Alloyium terrain path entirely (use Embeddium default).")
                .define("forceDisable", false);
        REGION_CACHE = b
                .comment("Per-region Indirect command cache (recommended ON).")
                .define("regionCache", true);
        CPU_CULL = b
                .comment("Extra CPU frustum/cone cull when building Indirect commands (usually redundant).")
                .define("cpuCull", false);
        CPU_CMD_THRESHOLD = b
                .comment("Meshlets above this use GPU Compute A+B'; below uses CPU Indirect.")
                .defineInRange("cpuCmdThreshold", 768, 1, 65536);
        VERBOSE_LOGGING = b
                .comment("Extra logs, login chat, [Perf] lines, detailed F3. Default OFF (warnings only).")
                .define("verboseLogging", false);
        b.pop();

        b.push("hiz");
        HIZ = b
                .comment("Temporal Hi-Z occlusion (opt-in; historically hurt FPS on GTX 1060-class).")
                .define("enabled", false);
        HIZ_FORCE_GPU = b
                .comment("When Hi-Z is ready, force GPU Indirect path even for small N.")
                .define("forceGpu", false);
        HIZ_BIAS = b
                .comment("Hi-Z depth bias.")
                .defineInRange("bias", 0.002d, 0.0d, 1.0d);
        HIZ_DOWNSAMPLE = b
                .comment("Hi-Z pyramid downsample factor (2 = half-res).")
                .defineInRange("downsample", 2, 1, 8);
        HIZ_EVERY = b
                .comment("Rebuild Hi-Z every N frames.")
                .defineInRange("buildEvery", 2, 1, 60);
        b.pop();

        b.push("oculus");
        IRIS_FALLBACK = b
                .comment("While a shader pack is active, do not intercept terrain (Embeddium/Iris native path).")
                .define("irisFallback", false);
        b.pop();

        b.push("debug");
        DEBUG_CULL = b
                .comment("GPU cull counter readback + CPU mirror (stalls GPU). Log output also needs verboseLogging.")
                .define("debugCull", false);
        CULL_FRUSTUM = b
                .comment("Enable frustum tests on debug/GPU cull paths.")
                .define("cullFrustum", true);
        CULL_CONE = b
                .comment("Enable normal-cone tests on debug/GPU cull paths.")
                .define("cullCone", true);
        b.pop();

        SPEC = b.build();
    }

    private AlloyiumConfig() {}

    public static boolean forceDisable() {
        return FORCE_DISABLE.get();
    }

    public static boolean regionCache() {
        return REGION_CACHE.get();
    }

    public static boolean cpuCull() {
        return CPU_CULL.get();
    }

    public static int cpuCmdThreshold() {
        return CPU_CMD_THRESHOLD.get();
    }

    public static boolean verboseLogging() {
        return VERBOSE_LOGGING.get();
    }

    public static boolean hiz() {
        return HIZ.get();
    }

    public static boolean hizForceGpu() {
        return HIZ_FORCE_GPU.get();
    }

    public static float hizBias() {
        return HIZ_BIAS.get().floatValue();
    }

    public static int hizDownsample() {
        return HIZ_DOWNSAMPLE.get();
    }

    public static int hizEvery() {
        return HIZ_EVERY.get();
    }

    public static boolean irisFallback() {
        return IRIS_FALLBACK.get();
    }

    public static boolean debugCull() {
        return DEBUG_CULL.get();
    }

    public static boolean cullFrustum() {
        return CULL_FRUSTUM.get();
    }

    public static boolean cullCone() {
        return CULL_CONE.get();
    }
}
