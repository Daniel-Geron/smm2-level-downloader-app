package io.github.daniel_geron.smm2injector

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
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

/** Local app state: the chosen save folder, which slots we've injected into, and backups. */
class AppStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString("tree_uri", null)?.let(Uri::parse)
        set(value) = prefs.edit { putString("tree_uri", value?.toString()) }

    var saveLocation: SaveLocation?
        get() {
            val id = prefs.getString("save_doc_id", null) ?: return null
            return SaveLocation(
                docId = id,
                displayPath = prefs.getString("save_path", "") ?: "",
                profile = prefs.getString("save_profile", "") ?: "",
            )
        }
        set(value) = prefs.edit {
            putString("save_doc_id", value?.docId)
            putString("save_path", value?.displayPath)
            putString("save_profile", value?.profile)
        }

    /** Whether downloaded levels are kept on the device (off by default). */
    var saveLevels: Boolean
        get() = prefs.getBoolean("save_levels", false)
        set(value) = prefs.edit { putBoolean("save_levels", value) }

    // ---------------------------------------------------------------- history

    fun loadHistory(): Map<Int, HistoryEntry> {
        val json = JSONObject(prefs.getString("history", "{}") ?: "{}")
        return json.keys().asSequence().associate { key ->
            val e = json.getJSONObject(key)
            key.toInt() to HistoryEntry(e.optString("code"), e.optString("name"), e.optLong("time"))
        }
    }

    fun recordInjection(slot: Int, entry: HistoryEntry) {
        val json = JSONObject(prefs.getString("history", "{}") ?: "{}")
        json.put(
            slot.toString(),
            JSONObject().put("code", entry.code).put("name", entry.name).put("time", entry.time),
        )
        prefs.edit { putString("history", json.toString()) }
    }

    fun clearHistory() = prefs.edit { remove("history") }

    // ---------------------------------------------------------------- backups

    private val backupRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")

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
