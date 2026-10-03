package com.pocketpad.data.profile

object ButtonMapping {
    const val BUTTON_COUNT = 15

    val labels = listOf(
        "D-pad Up", "D-pad Down", "D-pad Left", "D-pad Right",
        "Face Bottom", "Face Right", "Face Left", "Face Top",
        "L1", "R1", "Start", "Select", "Home", "L3", "R3"
    )

    fun identity(): Map<Int, Int> = (0 until BUTTON_COUNT).associateWith { it }

    fun decode(encoded: String): Map<Int, Int> {
        if (encoded.isBlank() || encoded == "{}") return identity()
        val pairs = encoded.split(",").map { item ->
            val fields = item.split(":")
            require(fields.size == 2) { "Invalid saved button mapping." }
            val source = fields[0].toInt()
            val target = fields[1].toInt()
            require(source in 0 until BUTTON_COUNT && target in 0 until BUTTON_COUNT) {
                "Saved button mapping is outside the supported range."
            }
            source to target
        }
        val mapping = pairs.toMap()
        require(mapping.size == BUTTON_COUNT && mapping.keys == (0 until BUTTON_COUNT).toSet()) {
            "Saved button mapping is incomplete."
        }
        require(mapping.values.toSet().size == BUTTON_COUNT) {
            "Saved button mapping contains duplicate outputs."
        }
        return mapping
    }

    fun encode(mapping: Map<Int, Int>): String {
        validate(mapping)
        return (0 until BUTTON_COUNT).joinToString(",") { "$it:${mapping.getValue(it)}" }
    }

    fun reassign(mapping: Map<Int, Int>, source: Int, target: Int): Map<Int, Int> {
        validate(mapping)
        require(source in 0 until BUTTON_COUNT && target in 0 until BUTTON_COUNT) {
            "Button mapping index is outside the supported range."
        }
        val previousTarget = mapping.getValue(source)
        val otherSource = mapping.entries.firstOrNull { it.value == target }?.key
        return mapping.toMutableMap().apply {
            this[source] = target
            if (otherSource != null && otherSource != source) this[otherSource] = previousTarget
        }
    }

    fun translate(buttons: Int, mapping: Map<Int, Int>): Int {
        validate(mapping)
        var translated = 0
        for (source in 0 until BUTTON_COUNT) {
            if (buttons and (1 shl source) != 0) translated = translated or (1 shl mapping.getValue(source))
        }
        return translated
    }

    private fun validate(mapping: Map<Int, Int>) {
        require(mapping.keys == (0 until BUTTON_COUNT).toSet()) { "Button mapping must define every button." }
        require(mapping.values.all { it in 0 until BUTTON_COUNT }) { "Button mapping target is invalid." }
        require(mapping.values.toSet().size == BUTTON_COUNT) { "Each button must have a unique output." }
    }
}
