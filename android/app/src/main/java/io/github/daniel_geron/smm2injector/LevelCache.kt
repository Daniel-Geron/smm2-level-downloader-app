package io.github.daniel_geron.smm2injector

import android.content.Context
import java.io.File

data class SavedLevel(val code: String, val info: MariOverApi.LevelInfo?, val hasData: Boolean, val time: Long)

/**
 * Levels saved on the device so they can be previewed and injected while the MariOver server is
 * down. Levels can't be changed after they're uploaded, so a saved copy never goes stale.
 *
 * Each level is a folder named after its code holding data.bcd, info.json and thumb.jpg.
 */
class LevelCache(context: Context) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "levels")

    private fun dir(code: String) = File(root, code)

    fun data(code: String): ByteArray? =
        File(dir(code), DATA).takeIf { it.exists() }?.readBytes()?.takeIf(MariOverApi::isCourseData)

    fun infoJson(code: String): String? = File(dir(code), INFO).takeIf { it.exists() }?.readText()

    fun thumbnail(code: String): ByteArray? = File(dir(code), THUMB).takeIf { it.exists() }?.readBytes()

    fun saveData(code: String, data: ByteArray) = write(code, DATA, data)

    fun saveInfo(code: String, json: String) = write(code, INFO, json.toByteArray())

    fun saveThumbnail(code: String, jpg: ByteArray) = write(code, THUMB, jpg)

    fun list(): List<SavedLevel> =
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .map { d ->
                val code = d.name
                SavedLevel(
                    code = code,
                    info = infoJson(code)?.let { runCatching { MariOverApi.parseLevelInfo(code, it) }.getOrNull() },
                    hasData = File(d, DATA).exists(),
                    time = d.lastModified(),
                )
            }
            .sortedByDescending { it.time }

    fun delete(code: String) {
        dir(code).deleteRecursively()
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    private fun write(code: String, name: String, bytes: ByteArray) {
        val d = dir(code).apply { mkdirs() }
        // Write to a temp file first so a crash never leaves a half-written level behind.
        val tmp = File(d, "$name.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(File(d, name))
        d.setLastModified(System.currentTimeMillis())
    }

    private companion object {
        const val DATA = "data.bcd"
        const val INFO = "info.json"
        const val THUMB = "thumb.jpg"
    }
}
