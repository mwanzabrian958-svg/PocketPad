package com.pocketpad.protocol

import kotlin.math.pow

data class ControllerState(
    val buttons: Int = 0,
    val leftX: Float = 0f,
    val leftY: Float = 0f,
    val rightX: Float = 0f,
    val rightY: Float = 0f,
    val leftTrigger: Float = 0f,
    val rightTrigger: Float = 0f
) {
    fun withButton(mask: Int, pressed: Boolean) = copy(
        buttons = if (pressed) buttons or mask else buttons and mask.inv()
    )

    /**
     * Applies the dead zone to the raw physical stick position, then shapes the response so
     * that full deflection still reaches full scale at every sensitivity. Scaling before the
     * dead zone would both shrink the usable range and, at low sensitivity, drop the whole
     * stick inside the dead zone.
     *
     * At the default sensitivity of 1 the exponent is 1, so this is a plain dead zone.
     */
    fun shaped(deadZone: Float = 0.15f, sensitivity: Float = 1f): ControllerState = copy(
        leftX = shapeAxis(leftX, deadZone, sensitivity),
        leftY = shapeAxis(leftY, deadZone, sensitivity),
        rightX = shapeAxis(rightX, deadZone, sensitivity),
        rightY = shapeAxis(rightY, deadZone, sensitivity),
        leftTrigger = leftTrigger.coerceIn(0f, 1f),
        rightTrigger = rightTrigger.coerceIn(0f, 1f)
    )

    private fun shapeAxis(value: Float, deadZone: Float, sensitivity: Float): Float {
        val deadZoned = applyDeadZone(value, deadZone)
        if (deadZoned == 0f) return 0f
        val exponent = 1f + (1f - sensitivity.coerceIn(0f, 1f)) * CURVE_RANGE
        val magnitude = kotlin.math.abs(deadZoned)
        return kotlin.math.sign(deadZoned) * magnitude.toDouble().pow(exponent.toDouble()).toFloat()
    }

    private fun applyDeadZone(value: Float, deadZone: Float): Float {
        val magnitude = value.coerceIn(-1f, 1f)
        val threshold = deadZone.coerceIn(0f, 0.95f)
        if (kotlin.math.abs(magnitude) <= threshold) return 0f
        return (magnitude - kotlin.math.sign(magnitude) * threshold) / (1f - threshold)
    }

    private companion object {
        /**
         * How much response-curve shaping is applied at zero sensitivity. Full deflection
         * always maps to 1.0 regardless of sensitivity; this only affects feel near center.
         */
        const val CURVE_RANGE = 3f
    }
}
