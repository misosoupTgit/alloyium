package com.github.misosoupTgit.alloyium.gl;

import com.github.misosoupTgit.alloyium.Alloyium;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * R4 decision: adopt {@code GL_ARB_indirect_parameters} / GL 4.6
 * {@code MultiDrawElementsIndirectCount} when present.
 * <p>
 * Rationale (DESIGN §4 / ポストモーテム): GPU の visibleCount を drawcount に直接渡し、
 * count=0 パッドの Command Processor 負荷を避ける。NV 専用ではなく主要ベンダー共通。
 * 非対応 GPU は従来の Multidraw(max) + no-op にフォールバック（機能は維持、効率のみ劣後）。
 */
public final class IndirectParametersPolicy {
    public enum Decision {
        ADOPTED_CORE_46,
        ADOPTED_ARB,
        FALLBACK_MULTIDRAW
    }

    private static Decision decision = Decision.FALLBACK_MULTIDRAW;

    private IndirectParametersPolicy() {}

    public static void evaluate(GLCapabilities cap) {
        if (cap.OpenGL46) {
            decision = Decision.ADOPTED_CORE_46;
            Alloyium.HAS_INDIRECT_COUNT = true;
        } else if (cap.GL_ARB_indirect_parameters) {
            decision = Decision.ADOPTED_ARB;
            Alloyium.HAS_INDIRECT_COUNT = true;
        } else {
            decision = Decision.FALLBACK_MULTIDRAW;
            Alloyium.HAS_INDIRECT_COUNT = false;
        }
        Alloyium.logVerbose("R4 IndirectParameters: {} (CountARB={})", decision, Alloyium.HAS_INDIRECT_COUNT);
    }

    public static Decision decision() {
        return decision;
    }

    public static boolean useIndirectCount() {
        return Alloyium.HAS_INDIRECT_COUNT;
    }
}
