package com.pocketpad.protocol

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

    fun normalized(deadZone: Float = 0.15f): ControllerState = copy(
        leftX = applyDeadZone(leftX, deadZone),
        leftY = applyDeadZone(leftY, deadZone),
        rightX = applyDeadZone(rightX, deadZone),
        rightY = applyDeadZone(rightY, deadZone),
        leftTrigger = leftTrigger.coerceIn(0f, 1f),
        rightTrigger = rightTrigger.coerceIn(0f, 1f)
    )

    private fun applyDeadZone(value: Float, deadZone: Float): Float {
        val magnitude = value.coerceIn(-1f, 1f)
        val threshold = deadZone.coerceIn(0f, 0.95f)
        if (kotlin.math.abs(magnitude) <= threshold) return 0f
        return (magnitude - kotlin.math.sign(magnitude) * threshold) / (1f - threshold)
    }
}
