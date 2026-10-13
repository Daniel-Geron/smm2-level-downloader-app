package io.github.daniel_geron.smm2injector

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Public MariOver API hosted by TheGreatRambler (https://github.com/TheGreatRambler/MariOver). */
object MariOverApi {
    private const val BASE = "https://tgrcode.com/mm2"

    /** Every course_data_XXX.bcd file is exactly this size. */
    const val COURSE_DATA_SIZE = 376832

    data class LevelInfo(
        val code: String,
        val name: String,
        val description: String,
        val gameStyle: String,
        val theme: String,
        val difficulty: String,
        val maker: String,
        val clearRate: String,
        val likes: Int,
        val plays: Int,
    )

    /** Strips dashes/spaces and upper-cases, so "abc-def-ghi" becomes "ABCDEFGHI". */
    fun normalizeCode(input: String): String =
        input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun isValidCode(code: String): Boolean = code.length == 9

    fun fetchLevelInfo(code: String): LevelInfo {
        val json = JSONObject(String(get("$BASE/level_info/${code.lowercase()}"), Charsets.UTF_8))
        return LevelInfo(
            code = code,
            name = json.optString("name", "Unknown"),
            description = json.optString("description", ""),
            gameStyle = json.optString("game_style_name", "?"),
            theme = json.optString("theme_name", "?"),
            difficulty = json.optString("difficulty_name", "?"),
            maker = json.optJSONObject("uploader")?.optString("name") ?: "?",
            clearRate = json.optString("clear_rate_pretty", "?"),
            likes = json.optInt("likes"),
            plays = json.optInt("plays"),
        )
    }

    fun fetchThumbnail(code: String): ByteArray? =
        try {
            get("$BASE/level_thumbnail/${code.lowercase()}")
        } catch (e: IOException) {
            null
        }

    /** Downloads the course_data file and makes sure it really is one. */
    fun fetchLevelData(code: String): ByteArray {
        val data = get("$BASE/level_data/${code.lowercase()}")
        val magic = if (data.size >= 16) String(data, 12, 4, Charsets.US_ASCII) else ""
        if (data.size != COURSE_DATA_SIZE || magic != "SCDL") {
            throw IOException("The server returned something that isn't a course file (${data.size} bytes).")
        }
        return data
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "SMM2LevelInjector-Android")
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val body = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
                val apiError = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw IOException(
                    if (!apiError.isNullOrBlank()) apiError else "Server returned HTTP $status"
                )
            }
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
