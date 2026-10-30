package com.example.homeezch.eva

/** Level of dynamic wallpaper and EVA effects. Ordered from cheapest to richest. */
enum class GraphicsTier { STATIC, AMBIENT, CINEMATIC }


/** Renderer budget for one tier. */
data class EvaQuality(
    val ribbons: Int,
    val segments: Int,
    val glowPasses: Int,
    val maxFps: Int,
    val particles: Int,
    val bloom: Boolean,
    val platform: Boolean,
    /** Frame cap for resting states (IDLE/OFFLINE); 0 freezes the orb until something happens. */
    val idleFps: Int
) {
    companion object {
        fun of(tier: GraphicsTier) = when (tier) {
            // 1–1.5 GB TVs: frozen idle frame; interaction states still animate at a low rate.
            GraphicsTier.STATIC -> EvaQuality(4, 40, 2, 20, 0, bloom = false, platform = true, idleFps = 0)
            GraphicsTier.AMBIENT -> EvaQuality(4, 56, 2, 30, 16, bloom = true, platform = true, idleFps = 24)
            GraphicsTier.CINEMATIC -> EvaQuality(6, 72, 3, 60, 40, bloom = true, platform = true, idleFps = 30)
        }
    }
}

