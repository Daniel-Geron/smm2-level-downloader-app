package io.github.daniel_geron.smm2injector

import java.io.File
import java.io.IOException

/** Super Mario Maker 2's title ID; its save folder is named after it. */
const val SMM2_TITLE_ID = "01009B90006DC000"

/** Slots 0-119 are "My Courses" (editable), 120-179 are "Downloaded Courses" (play only). */
const val MY_COURSES_SLOTS = 120
const val TOTAL_SLOTS = 180

fun courseDataName(slot: Int) = "course_data_%03d.bcd".format(slot)

private val osName = System.getProperty("os.name").lowercase()
val isWindows = "win" in osName
val isMac = "mac" in osName
private val home = File(System.getProperty("user.home"))

/** The platform's per-user app data folder (%APPDATA% on Windows). */
private fun dataHome(): File = when {
    isWindows -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
    isMac -> File(home, "Library/Application Support")
    else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
}

private fun configHome(): File = when {
    isWindows || isMac -> dataHome()
    else -> System.getenv("XDG_CONFIG_HOME")?.let(::File) ?: File(home, ".config")
}

/** Where this app keeps its settings, backups and saved levels. */
val appDir: File = File(dataHome(), if (isWindows || isMac) "SMM2 Level Downloader" else "smm2-level-downloader")

/** The emulators the app can inject into. Both keep the save as a plain folder of files. */
enum class Emulator(val label: String) {
    EDEN("Eden"),
    RYUJINX("Ryujinx");

    /** The emulator's data folder in its default (non-portable) install. */
    val defaultDataDirs: List<File>
        get() = when (this) {
            EDEN -> listOf(File(dataHome(), "eden"))
            RYUJINX -> listOf(File(configHome(), "Ryujinx"))
        }

    /** Executable names, used to warn when the emulator is still running. */
    val processNames: List<String>
        get() = when (this) {
            EDEN -> listOf("eden", "eden.exe", "eden-qt", "eden-qt.exe")
            RYUJINX -> listOf("ryujinx", "ryujinx.exe", "ryujinx.ava", "ryujinx.ava.exe")
        }

    /** Whether the emulator is running right now (it could overwrite the save when it closes). */
    fun isRunning(): Boolean = runCatching {
        ProcessHandle.allProcesses().anyMatch { p ->
            val cmd = p.info().command().orElse(null) ?: return@anyMatch false
            File(cmd).name.lowercase() in processNames
        }
    }.getOrDefault(false)
}

/**
 * A Super Mario Maker 2 save folder. [mirror] is Ryujinx's working copy of the save, which gets the
 * same changes so the two never disagree.
 */
data class SaveLocation(val dir: File, val profile: String, val mirror: File? = null) {
    val displayPath: String get() = dir.absolutePath
}

/** Reads and writes a save folder. */
class SaveFolder(private val location: SaveLocation) {
    fun list(): List<String> =
        location.dir.listFiles()?.filter { it.isFile }?.map { it.name }
            ?: throw IOException("Couldn't open the save folder ${location.displayPath}")

    fun readFile(name: String): ByteArray = File(location.dir, name).readBytes()

    /** Overwrites [name] (creating it if needed). */
    fun writeFile(name: String, data: ByteArray) {
        write(File(location.dir, name), data)
        location.mirror?.takeIf { it.isDirectory }?.let { write(File(it, name), data) }
    }

    private fun write(target: File, data: ByteArray) {
        // Write next to the file and swap it in, so a crash never leaves a half-written course.
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Couldn't write ${target.name}. Is the emulator still running?")
            }
        }
    }
}

/** Finds SMM2 saves inside an emulator's data folder (or any folder the user picked). */
object SaveFinder {
    private const val MAX_DEPTH = 8
    private const val MAX_DIRS_TO_SEARCH = 6000

    /** Big folders that never contain save data; skipping them keeps the search fast. */
    private val SKIP_DIRS = setOf(
        "registered", "placehld", "contents", "shader", "load", "dump", "log", "logs", "cache",
        "screenshots", "sdmc", "keys", "amiibo", "gpu_drivers", "game_list", "crash_dumps", "games",
        "mods", "system", "sdcard", "firmware", "nca", "profiles", "temp",
    )

    /** Files every SMM2 save has, even before any course is made. */
    private val SMM2_FILES = listOf("save.dat", "quest.dat", "myworld.dat")

    fun find(emulator: Emulator, root: File): List<SaveLocation> {
        if (looksLikeSmm2Save(root)) return listOf(asLocation(emulator, root))
        return when (emulator) {
            Emulator.EDEN -> findEden(root)
            Emulator.RYUJINX -> findRyujinx(root)
        }
    }

    private fun looksLikeSmm2Save(dir: File) = SMM2_FILES.all { File(dir, it).isFile }

    /** A save folder the user picked directly. */
    private fun asLocation(emulator: Emulator, dir: File): SaveLocation {
        // Ryujinx keeps the committed save in "0" and a working copy in "1".
        val mirror = if (emulator == Emulator.RYUJINX && dir.name == "0") File(dir.parentFile, "1") else null
        return SaveLocation(dir, dir.parentFile?.name.orEmpty(), mirror?.takeIf { it.isDirectory })
    }

    /**
     * Eden (like yuzu) uses
     *   nand/user/save/0000000000000000/<user id>/01009B90006DC000/   (classic layout) or
     *   nand/user/save/account/<user uuid>/01009B90006DC000/0/        (newer layout).
     */
    private fun findEden(root: File): List<SaveLocation> {
        val found = mutableListOf<SaveLocation>()
        val queue = ArrayDeque(listOf(root to 0))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_DIRS_TO_SEARCH) {
            val (dir, depth) = queue.removeFirst()
            visited++
            for (child in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (!child.isDirectory) continue
                if (child.name.equals(SMM2_TITLE_ID, ignoreCase = true)) {
                    val inner = File(child, "0")
                    found += SaveLocation(if (inner.isDirectory) inner else child, dir.name)
                } else if (depth < MAX_DEPTH && child.name.lowercase() !in SKIP_DIRS) {
                    queue.addLast(child to depth + 1)
                }
            }
        }
        // Eden prefers the newer "account" layout when it exists.
        return found.sortedByDescending { "account" in it.dir.path.lowercase() }
    }

    /**
     * Ryujinx names save folders by number (bis/user/save/000000000000002a/0/), so the SMM2 one is
     * recognised by its files.
     */
    private fun findRyujinx(root: File): List<SaveLocation> {
        val saveRoots = listOf(File(root, "bis/user/save"), File(root, "user/save"), File(root, "save"), root)
            .filter { it.isDirectory }
        return saveRoots.firstNotNullOfOrNull { saves ->
            saves.listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
                .mapNotNull { id ->
                    val committed = File(id, "0")
                    if (looksLikeSmm2Save(committed)) {
                        SaveLocation(committed, id.name, File(id, "1").takeIf { it.isDirectory })
                    } else {
                        null
                    }
                }
                .takeIf { it.isNotEmpty() }
        }.orEmpty()
    }
}
