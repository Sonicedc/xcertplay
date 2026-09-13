package com.shilapi.xcertplay.airplay

/** User-adjustable display values with the same stepped ranges used by the settings UI. */
object AirPlayDisplaySettings {
    const val MIN_FPS = 30
    const val MAX_FPS = 60
    const val FPS_STEP = 5
    const val DEFAULT_FPS = MAX_FPS

    const val MIN_WIDTH_PHYSICAL_MM = 100
    const val MAX_WIDTH_PHYSICAL_MM = 400
    const val WIDTH_PHYSICAL_MM_STEP = 50
    const val DEFAULT_WIDTH_PHYSICAL_MM = 200

    fun sanitizeFps(value: Int): Int =
        snapToStep(value, MIN_FPS, MAX_FPS, FPS_STEP)

    fun sanitizeWidthPhysicalMm(value: Int): Int =
        snapToStep(
            value,
            MIN_WIDTH_PHYSICAL_MM,
            MAX_WIDTH_PHYSICAL_MM,
            WIDTH_PHYSICAL_MM_STEP,
        )

    fun fpsProgress(value: Int): Int =
        (sanitizeFps(value) - MIN_FPS) / FPS_STEP

    fun widthPhysicalMmProgress(value: Int): Int =
        (sanitizeWidthPhysicalMm(value) - MIN_WIDTH_PHYSICAL_MM) / WIDTH_PHYSICAL_MM_STEP

    private fun snapToStep(value: Int, minimum: Int, maximum: Int, step: Int): Int {
        val clamped = value.coerceIn(minimum, maximum)
        return (((clamped - minimum) + step / 2) / step) * step + minimum
    }
}
