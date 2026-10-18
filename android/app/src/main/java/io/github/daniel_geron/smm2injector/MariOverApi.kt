package io.github.daniel_geron.smm2injector

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Public MariOver API hosted by TheGreatRambler (https://github.com/TheGreatRambler/MariOver). */
object MariOverApi {
    /** tgrcode.com in release builds; debug builds can point at a test server (see build.gradle.kts). */
    private val BASE = BuildConfig.API_BASE_URL

    /** Every course_data_XXX.bcd file is exactly this size. */
    const val COURSE_DATA_SIZE = 376832

    /** A real level used to check whether the server can still reach Nintendo. */
    private const val STATUS_PROBE_CODE = "3ksv2kmdg"

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

    enum class ServerStatus { ONLINE, BUSY, DOWN }

    /** The server answered, but with an error about this level (e.g. "No course with that ID"). */
    class LevelException(message: String) : IOException(message)

    /** The server itself is failing or unreachable, so no level can be downloaded right now. */
    class ServerDownException(message: String, val status: ServerStatus) : IOException(message)

    /** Strips dashes/spaces and upper-cases, so "abc-def-ghi" becomes "ABCDEFGHI". */
    fun normalizeCode(input: String): String =
        input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun isValidCode(code: String): Boolean = code.length == 9

    /** Shows a 9-character code as "ABC-DEF-GHI". */
    fun formatCode(code: String): String = code.chunked(3).joinToString("-")

    fun fetchLevelInfoJson(code: String): String =
        String(get("$BASE/level_info/${code.lowercase()}"), Charsets.UTF_8)

    fun parseLevelInfo(code: String, json: String): LevelInfo {
        val obj = JSONObject(json)
        return LevelInfo(
            code = code,
            name = obj.optString("name", "Unknown"),
            description = obj.optString("description", ""),
            gameStyle = obj.optString("game_style_name", "?"),
            theme = obj.optString("theme_name", "?"),
            difficulty = obj.optString("difficulty_name", "?"),
            maker = obj.optJSONObject("uploader")?.optString("name") ?: "?",
            clearRate = obj.optString("clear_rate_pretty", "?"),
            likes = obj.optInt("likes"),
            plays = obj.optInt("plays"),
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
        if (!isCourseData(data)) {
            throw IOException("The server returned something that isn't a course file (${data.size} bytes).")
        }
        return data
    }

    fun isCourseData(data: ByteArray): Boolean =
        data.size == COURSE_DATA_SIZE && String(data, 12, 4, Charsets.US_ASCII) == "SCDL"

    /**
     * Asks for one level's info. Any answer from the API, even "no course with that ID",
     * means the server is working.
     */
    fun checkStatus(): ServerStatus =
        try {
            get("$BASE/level_info/$STATUS_PROBE_CODE", timeoutMs = 10_000)
            ServerStatus.ONLINE
        } catch (e: LevelException) {
            ServerStatus.ONLINE
        } catch (e: ServerDownException) {
            e.status
        } catch (e: IOException) {
            ServerStatus.DOWN
        }

    private fun get(url: String, timeoutMs: Int = 60_000): ByteArray {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = minOf(timeoutMs, 15_000)
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "SMM2LevelInjector-Android")
            }
        } catch (e: IOException) {
            throw serverDown("Couldn't connect to the level server. Check your internet connection.")
        }
        try {
            val status = try {
                conn.responseCode
            } catch (e: IOException) {
                throw serverDown("Couldn't reach the level server. Check your internet connection.")
            }
            if (status == HttpURLConnection.HTTP_OK) return conn.inputStream.use { it.readBytes() }

            val body = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            val apiError = runCatching { JSONObject(body).optString("error") }.getOrNull()
            when {
                status == 429 -> throw ServerDownException(
                    "The level server is getting too many requests right now (HTTP 429). " +
                        "Wait a minute and try again.",
                    ServerStatus.BUSY,
                )
                status >= 500 -> throw serverDown(
                    "The MariOver level server is having problems right now (HTTP $status). " +
                        "This isn't a problem with the app. Try again later."
                )
                !apiError.isNullOrBlank() -> throw LevelException(apiError)
                else -> throw IOException("The level server returned HTTP $status.")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun serverDown(message: String) = ServerDownException(message, ServerStatus.DOWN)
}
