package io.github.daniel_geron.smm2injector

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

data class HistoryEntry(val code: String, val name: String, val time: Long)

data class Backup(val dir: File, val label: String, val time: Long, val fileCount: Int)

/** Local app state: the chosen emulator and save folder, which slots we've injected into, and backups. */
class AppStore {
    private val settingsFile = File(appDir, "settings.json")
    private val settings: JSONObject =
        runCatching { JSONObject(settingsFile.readText()) }.getOrDefault(JSONObject())

    private fun save() {
        appDir.mkdirs()
        val tmp = File(appDir, "settings.json.tmp")
        tmp.writeText(settings.toString(2))
        if (!tmp.renameTo(settingsFile)) {
            settingsFile.delete()
            tmp.renameTo(settingsFile)
        }
    }

    private fun edit(block: JSONObject.() -> Unit) = synchronized(settings) {
        settings.block()
        save()
    }

    private fun string(key: String): String? = settings.optString(key, "").ifEmpty { null }

    var emulator: Emulator?
        get() = string("emulator")?.let { runCatching { Emulator.valueOf(it) }.getOrNull() }
        set(value) = edit { put("emulator", value?.name ?: "") }

    /** The emulator data folder the user picked, if it isn't the default one. */
    fun customRoot(emulator: Emulator): File? = string("root_${emulator.name}")?.let(::File)

    fun setCustomRoot(emulator: Emulator, dir: File?) = edit { put("root_${emulator.name}", dir?.absolutePath ?: "") }

    fun saveLocation(emulator: Emulator): SaveLocation? {
        val dir = string("save_${emulator.name}")?.let(::File) ?: return null
        return SaveLocation(
            dir = dir,
            profile = string("save_profile_${emulator.name}").orEmpty(),
            mirror = string("save_mirror_${emulator.name}")?.let(::File),
        )
    }

    fun setSaveLocation(emulator: Emulator, value: SaveLocation?) = edit {
        put("save_${emulator.name}", value?.dir?.absolutePath ?: "")
        put("save_profile_${emulator.name}", value?.profile ?: "")
        put("save_mirror_${emulator.name}", value?.mirror?.absolutePath ?: "")
    }

    /** Whether downloaded levels are kept on this PC (off by default). */
    var saveLevels: Boolean
        get() = settings.optBoolean("save_levels", false)
        set(value) = edit { put("save_levels", value) }

    /** Whether the first-launch welcome popup has been dismissed. */
    var welcomeShown: Boolean
        get() = settings.optBoolean("welcome_shown", false)
        set(value) = edit { put("welcome_shown", value) }

    /** Where the level archive lives, if the user moved it from the default folder. */
    var archiveRoot: String?
        get() = string("archive_root")
        set(value) = edit { put("archive_root", value ?: "") }

    // ---------------------------------------------------------------- history

    // Injections are remembered per save folder, since each emulator has its own slots.
    private fun historyKey(location: SaveLocation?) = "history:" + location?.dir?.absolutePath.orEmpty()

    fun loadHistory(location: SaveLocation?): Map<Int, HistoryEntry> {
        val json = settings.optJSONObject(historyKey(location)) ?: JSONObject()
        return json.keys().asSequence().associate { key ->
            val e = json.getJSONObject(key)
            key.toInt() to HistoryEntry(e.optString("code"), e.optString("name"), e.optLong("time"))
        }
    }

    fun recordInjection(location: SaveLocation, slot: Int, entry: HistoryEntry) = edit {
        val json = optJSONObject(historyKey(location)) ?: JSONObject().also { put(historyKey(location), it) }
        json.put(
            slot.toString(),
            JSONObject().put("code", entry.code).put("name", entry.name).put("time", entry.time),
        )
    }

    fun clearHistory(location: SaveLocation) = edit { remove(historyKey(location)) }

    // ---------------------------------------------------------------- backups

    private val backupRoot: File
        get() = File(appDir, "backups")

    /** Creates a new backup folder; [writeFiles] fills it via the supplied callback. */
    fun createBackup(label: String, writeFiles: ((name: String, data: ByteArray) -> Unit) -> Unit): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val dir = File(backupRoot, stamp)
        if (!dir.mkdirs()) throw IOException("Couldn't create backup folder")
        File(dir, LABEL_FILE).writeText(label)
        writeFiles { name, data -> File(dir, name).writeBytes(data) }
        return dir
    }

    fun listBackups(): List<Backup> =
        backupRoot.listFiles { f -> f.isDirectory }.orEmpty()
            .map { dir ->
                Backup(
                    dir = dir,
                    label = File(dir, LABEL_FILE).takeIf { it.exists() }?.readText() ?: dir.name,
                    time = dir.lastModified(),
                    fileCount = dir.listFiles().orEmpty().count { it.name != LABEL_FILE },
                )
            }
            .sortedByDescending { it.time }

    fun backupFiles(backup: Backup): List<File> =
        backup.dir.listFiles().orEmpty().filter { it.isFile && it.name != LABEL_FILE }

    fun deleteBackup(backup: Backup) {
        backup.dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- pre-loaded save

    /** Downloads one file of the repo's pre-loaded save (every slot already filled) into [dest]. */
    fun downloadPreloadedFile(name: String, dest: File) {
        val url = PRELOADED_SAVE_BASE_URL + URLEncoder.encode(name, "UTF-8")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Downloading $name failed (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val LABEL_FILE = "label.txt"

        private const val PRELOADED_SAVE_BASE_URL =
            "https://raw.githubusercontent.com/Daniel-Geron/smm2-level-downloader-app/main/Pre-loaded%20Save/Emulator/"

        val PRELOADED_SAVE_FILES: List<String> = buildList {
            for (i in 0 until TOTAL_SLOTS) add(courseDataName(i))
            for (i in 0 until TOTAL_SLOTS) add("course_thumb_%03d.btl".format(i))
            for (i in 0 until MY_COURSES_SLOTS) add("course_replay_%03d.dat".format(i))
            addAll(listOf("save.dat", "later.dat", "myworld.dat", "network.dat", "quest.dat"))
        }
    }
}
