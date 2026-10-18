package io.github.daniel_geron.smm2injector.archive

/**
 * Converts between SMM2 course codes and the data ids the archive is keyed by.
 * Algorithm: https://github.com/kinnay/NintendoClients/wiki/Data-Store-Codes#super-mario-maker-2
 */
object CourseCode {
    private const val CHARSET = "0123456789BCDFGHJKLMNPQRSTVWXY"
    private const val XOR = 0b00010110100000001110000001111100L

    /** Returns the data id for a 9-character course code, or null if it isn't a valid course code. */
    fun toDataId(code: String): Long? {
        var number = 0L
        for (c in code.uppercase().filter { it != '-' }.reversed()) {
            val v = CHARSET.indexOf(c)
            if (v < 0) return null
            number = number * 30 + v
        }
        val f = number and 0xFFF
        val isMaker = (number ushr 13) and 1L == 1L
        val c = (number ushr 14) and 0xFFFFF
        val checksum = (number ushr 34) and 0x3F
        val dataId = ((f shl 20) or c) xor XOR
        val valid = (number ushr 40) == 0b1000L && (number ushr 12) and 1L == 1L && !isMaker &&
            checksum == Math.floorMod(dataId - 31, 64L)
        return dataId.takeIf { valid }
    }

    /** Returns the 9-character course code (without dashes) for a data id. */
    fun fromDataId(dataId: Long): String {
        val x = dataId xor XOR
        val f = x ushr 20
        val c = x and 0xFFFFF
        var number = (0b1000L shl 40) or (Math.floorMod(dataId - 31, 64L) shl 34) or (c shl 14) or (1L shl 12) or f
        val sb = StringBuilder()
        while (number > 0) {
            sb.append(CHARSET[(number % 30).toInt()])
            number /= 30
        }
        while (sb.length < 9) sb.append('0')
        return sb.toString() // least significant digit first, which is how codes are written
    }
}

/** Display names for the numeric fields in the archive (from MariOver). */
object LevelNames {
    val styles = listOf("SMB1", "SMB3", "SMW", "NSMBU", "SM3DW")
    val themes = listOf(
        "Overworld", "Underground", "Castle", "Airship", "Underwater",
        "Ghost house", "Snow", "Desert", "Sky", "Forest",
    )
    val difficulties = listOf("Easy", "Normal", "Expert", "Super expert")
    val tags = listOf(
        "None", "Standard", "Puzzle solving", "Speedrun", "Autoscroll", "Auto mario",
        "Short and sweet", "Multiplayer versus", "Themed", "Music", "Art", "Technical",
        "Shooter", "Boss battle", "Single player", "Link",
    )

    fun style(i: Int) = styles.getOrElse(i) { "?" }
    fun theme(i: Int) = themes.getOrElse(i) { "?" }
    fun difficulty(i: Int) = difficulties.getOrElse(i) { "?" }
    fun tag(i: Int) = tags.getOrElse(i) { "?" }
}
