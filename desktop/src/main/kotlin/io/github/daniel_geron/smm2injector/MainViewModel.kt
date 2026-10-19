package io.github.daniel_geron.smm2injector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.daniel_geron.smm2injector.archive.ArchiveLevel
import io.github.daniel_geron.smm2injector.archive.CourseCode
import io.github.daniel_geron.smm2injector.archive.LevelArchive
import io.github.daniel_geron.smm2injector.archive.LevelNames
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Where the level preview came from. */
enum class PreviewSource { SERVER, DEVICE, ARCHIVE }

sealed interface DialogState {
    data class Error(val message: String) : DialogState
    data class Success(val message: String) : DialogState
    data class ConfirmEmptySlot(val slot: Int) : DialogState
    data object ConfirmPreloaded : DialogState
    data class ConfirmRestore(val backup: Backup) : DialogState
    data class ChooseSave(val candidates: List<SaveLocation>) : DialogState
    data object ConfirmDeleteSavedLevels : DialogState
    /** The emulator is running; [action] continues anyway. */
    data class EmulatorRunning(val emulator: Emulator, val action: () -> Unit) : DialogState
}

class MainViewModel(private val store: AppStore, private val archive: LevelArchive) {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cache = LevelCache()

    /** The emulator whose save is used. Null until the user picks one. */
    var emulator by mutableStateOf(store.emulator)
        private set
    /** The emulator's data folder (the default one, or one the user picked). */
    var emulatorRoot by mutableStateOf<File?>(null)
        private set
    var saveLocation by mutableStateOf<SaveLocation?>(null)
        private set
    /** Names of the files currently in the save folder. */
    var saveFileNames by mutableStateOf<Set<String>>(emptySet())
        private set

    var codeInput by mutableStateOf("")
    var slotInput by mutableStateOf("")

    var levelInfo by mutableStateOf<MariOverApi.LevelInfo?>(null)
        private set
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
        private set
    var previewSource by mutableStateOf(PreviewSource.SERVER)
        private set

    /** Null while checking. */
    var serverStatus by mutableStateOf<MariOverApi.ServerStatus?>(null)
        private set
    var serverCheckedAt by mutableStateOf(0L)
        private set

    var saveLevels by mutableStateOf(store.saveLevels)
        private set

    /** Shown once, on the first launch. */
    var showWelcome by mutableStateOf(!store.welcomeShown)
        private set

    fun dismissWelcome() {
        showWelcome = false
        store.welcomeShown = true
    }
    var savedLevels by mutableStateOf(cache.list())
        private set

    var history by mutableStateOf<Map<Int, HistoryEntry>>(emptyMap())
        private set
    var backups by mutableStateOf(store.listBackups())
        private set

    /** Non-null while a long-running task is in progress; shown as the progress text. */
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var dialog by mutableStateOf<DialogState?>(null)

    /** "Eden" or "Ryujinx", or a neutral word before one is picked. */
    val emulatorName: String get() = emulator?.label ?: "the emulator"

    init {
        checkServer()
        emulator?.let { selectEmulator(it) }
    }

    // ---------------------------------------------------------------- save folder

    /** Switches to [emulator], reusing its remembered save or looking for one. */
    fun selectEmulator(emulator: Emulator) {
        this.emulator = emulator
        store.emulator = emulator
        emulatorRoot = store.customRoot(emulator) ?: emulator.defaultDataDirs.firstOrNull { it.isDirectory }
        val remembered = store.saveLocation(emulator)?.takeIf { it.dir.isDirectory }
        if (remembered != null) {
            setSave(remembered)
        } else {
            setSave(null)
            if (emulatorRoot != null) scanForSaves(quiet = true)
        }
    }

    /** The user picked the emulator's folder (or the save folder itself) by hand. */
    fun onFolderPicked(dir: File) {
        val emulator = emulator ?: return
        store.setCustomRoot(emulator, dir)
        emulatorRoot = dir
        setSave(null)
        scanForSaves()
    }

    /** Forgets a hand-picked folder and goes back to the emulator's default location. */
    fun useDefaultFolder() {
        val emulator = emulator ?: return
        store.setCustomRoot(emulator, null)
        store.setSaveLocation(emulator, null)
        selectEmulator(emulator)
    }

    /** [quiet] skips the "not found" message, for the automatic search when switching emulator. */
    fun scanForSaves(quiet: Boolean = false) {
        val emulator = emulator ?: return
        val root = emulatorRoot
        if (root == null) {
            if (!quiet) showError(notFoundMessage(emulator, null))
            return
        }
        runTask("Looking for Super Mario Maker 2 save data…") {
            val found = SaveFinder.find(emulator, root)
            when {
                found.isEmpty() -> if (!quiet) dialog = DialogState.Error(notFoundMessage(emulator, root))
                found.size == 1 -> setSave(found.single())
                else -> dialog = DialogState.ChooseSave(found)
            }
        }
    }

    private fun notFoundMessage(emulator: Emulator, root: File?): String {
        val name = emulator.label
        val marker = if (emulator == Emulator.EDEN) "nand" else "bis"
        return (if (root == null) "$name's folder wasn't found on this PC." else "No Super Mario Maker 2 save was found in ${root.path}.") +
            "\n\nStart Super Mario Maker 2 in $name once (get to the title screen so it creates a save) and " +
            "close $name, then press Rescan.\n\nIf $name is portable or uses a custom folder, press Choose folder " +
            "and select its data folder (the one with a \"$marker\" folder in it)."
    }

    fun chooseSave(location: SaveLocation) {
        dialog = null
        setSave(location)
    }

    private fun setSave(location: SaveLocation?) {
        saveLocation = location
        emulator?.let { store.setSaveLocation(it, location) }
        saveFileNames = emptySet()
        history = store.loadHistory(location)
        if (location != null) refreshSaveFiles()
    }

    fun refreshSaveFiles() {
        val location = saveLocation ?: return
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) { runCatching { SaveFolder(location).list().toSet() }.getOrNull() }
            if (names == null) {
                // The folder moved or was deleted (e.g. the emulator was reinstalled); look again.
                setSave(null)
                scanForSaves(quiet = true)
            } else {
                saveFileNames = names
            }
        }
    }

    /**
     * Runs [action] now, or asks first if the emulator is open: it could overwrite the save with
     * the old data when it saves or closes.
     */
    private fun whenEmulatorClosed(action: () -> Unit) {
        val emulator = emulator
        if (emulator != null && emulator.isRunning()) {
            dialog = DialogState.EmulatorRunning(emulator, action)
        } else {
            action()
        }
    }

    // ---------------------------------------------------------------- level lookup

    fun lookUpLevel() {
        val code = MariOverApi.normalizeCode(codeInput)
        if (!MariOverApi.isValidCode(code)) {
            dialog = DialogState.Error("A level code has 9 letters and numbers, like ABC-DEF-GHI.")
            return
        }
        levelInfo = null
        thumbnail = null
        previewSource = PreviewSource.SERVER
        runTask("Looking up ${MariOverApi.formatCode(code)}…") {
            previewSource = try {
                val json = MariOverApi.fetchLevelInfoJson(code)
                recordServerStatus(MariOverApi.ServerStatus.ONLINE)
                levelInfo = MariOverApi.parseLevelInfo(code, json)
                val thumb = MariOverApi.fetchThumbnail(code)
                if (saveLevels) {
                    cache.saveInfo(code, json)
                    thumb?.let { cache.saveThumbnail(code, it) }
                    // Grab the level itself too, so it can be injected later even if the server goes down.
                    if (cache.data(code) == null) {
                        busyMessage = "Saving ${MariOverApi.formatCode(code)} on this PC…"
                        runCatching { cache.saveData(code, MariOverApi.fetchLevelData(code)) }
                    }
                    savedLevels = cache.list()
                }
                showThumbnail(thumb ?: cache.thumbnail(code))
                PreviewSource.SERVER
            } catch (e: MariOverApi.ServerDownException) {
                recordServerStatus(e.status)
                val json = cache.infoJson(code)
                if (json != null) {
                    levelInfo = MariOverApi.parseLevelInfo(code, json)
                    showThumbnail(cache.thumbnail(code))
                    PreviewSource.DEVICE
                } else {
                    busyMessage = "Server is down, checking the 2022 level archive…"
                    val level = archiveLookup(code) ?: throw notSavedError(e)
                    levelInfo = MariOverApi.parseLevelInfo(code, archiveInfoJson(level))
                    PreviewSource.ARCHIVE
                }
            }
        }
    }

    private fun showThumbnail(jpg: ByteArray?) {
        thumbnail = jpg?.let { runCatching { ImageIO.read(it.inputStream()) }.getOrNull() }?.toComposeImageBitmap()
    }

    private fun notSavedError(e: MariOverApi.ServerDownException) = MariOverApi.ServerDownException(
        e.message + "\n\nThis level isn't saved on this PC or in the 2022 level archive, so it can't be " +
            "used until the server is back.",
        e.status,
    )

    private fun archiveLookup(code: String): ArchiveLevel? {
        val id = CourseCode.toDataId(code) ?: return null
        if (!archive.mightContain(id)) return null
        return runCatching { archive.lookup(id) }.getOrNull()
    }

    private fun archiveCourse(code: String): ByteArray? {
        val id = CourseCode.toDataId(code) ?: return null
        if (!archive.mightContain(id)) return null
        return archive.courseFile(id)
    }

    /** The archive level in the same JSON shape as MariOver's level_info, so it can be saved and shown alike. */
    private fun archiveInfoJson(level: ArchiveLevel): String = JSONObject()
        .put("name", level.name)
        .put("description", runCatching { archive.description(level.dataId) }.getOrNull().orEmpty())
        .put("game_style_name", LevelNames.style(level.style))
        .put("theme_name", LevelNames.theme(level.theme))
        .put("difficulty_name", LevelNames.difficulty(level.difficulty))
        .put("uploader", JSONObject().put("name", "unknown (from the 2022 archive)"))
        .put("clear_rate_pretty", "%.2f%%".format(level.clearRate))
        .put("likes", level.likes)
        .put("plays", level.plays)
        .put("tags_name", JSONArray(listOf(LevelNames.tag(level.tag1), LevelNames.tag(level.tag2))))
        .toString()

    // ---------------------------------------------------------------- server status

    fun checkServer() {
        serverStatus = null
        viewModelScope.launch {
            recordServerStatus(withContext(Dispatchers.IO) { MariOverApi.checkStatus() })
        }
    }

    private fun recordServerStatus(status: MariOverApi.ServerStatus) {
        serverStatus = status
        serverCheckedAt = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- saved levels

    fun updateSaveLevels(enabled: Boolean) {
        saveLevels = enabled
        store.saveLevels = enabled
    }

    fun useSavedLevel(code: String) {
        codeInput = code
        lookUpLevel()
    }

    fun deleteSavedLevel(code: String) {
        cache.delete(code)
        savedLevels = cache.list()
    }

    fun deleteAllSavedLevels() {
        dialog = null
        cache.deleteAll()
        savedLevels = cache.list()
    }

    // ---------------------------------------------------------------- inject

    fun parsedSlot(): Int? = slotInput.trim().toIntOrNull()?.takeIf { it in 0 until TOTAL_SLOTS }

    /** Set when injecting from the archive browser, so the archive is used instead of MariOver. */
    private var pendingArchiveLevel: ArchiveLevel? = null

    fun injectFromArchive(level: ArchiveLevel, slot: Int) {
        codeInput = level.code
        slotInput = slot.toString()
        levelInfo = null
        pendingArchiveLevel = level
        inject()
    }

    fun inject(confirmedEmptySlot: Boolean = false, confirmedRunning: Boolean = false) {
        dialog = null
        val code = MariOverApi.normalizeCode(codeInput)
        val slot = parsedSlot()
        val location = saveLocation
        when {
            location == null ->
                return showError("Choose your emulator and its Super Mario Maker 2 save first.")
            !MariOverApi.isValidCode(code) ->
                return showError("A level code has 9 letters and numbers, like ABC-DEF-GHI.")
            slot == null ->
                return showError("Pick a slot from 0 to ${TOTAL_SLOTS - 1}.")
        }
        slot!!
        val fileName = courseDataName(slot)
        if (fileName !in saveFileNames && !confirmedEmptySlot) {
            dialog = DialogState.ConfirmEmptySlot(slot)
            return
        }
        if (!confirmedRunning) {
            return whenEmulatorClosed { inject(confirmedEmptySlot = true, confirmedRunning = true) }
        }

        runTask("Downloading ${MariOverApi.formatCode(code)}…") {
            val fromArchive = pendingArchiveLevel?.takeIf { it.code == code }
            pendingArchiveLevel = null
            // A saved copy is identical to the server's (levels can't change after upload), so use it first.
            val data = cache.data(code)
                ?: fromArchive?.let {
                    busyMessage = "Getting ${MariOverApi.formatCode(code)} from the level archive…"
                    archive.courseFile(it.dataId) ?: throw IOException("This level isn't in the archive.")
                }
                ?: try {
                    MariOverApi.fetchLevelData(code).also { recordServerStatus(MariOverApi.ServerStatus.ONLINE) }
                } catch (e: MariOverApi.ServerDownException) {
                    recordServerStatus(e.status)
                    busyMessage = "Server is down, checking the 2022 level archive…"
                    archiveCourse(code) ?: throw notSavedError(e)
                }
            val archiveJson = fromArchive?.let(::archiveInfoJson)
            if (saveLevels && archiveJson != null && cache.infoJson(code) == null) cache.saveInfo(code, archiveJson)
            val info = levelInfo?.takeIf { it.code == code }
                ?: archiveJson?.let { MariOverApi.parseLevelInfo(code, it) }
                ?: cache.infoJson(code)?.let { MariOverApi.parseLevelInfo(code, it) }
                ?: runCatching {
                    MariOverApi.fetchLevelInfoJson(code).also { json ->
                        if (saveLevels) cache.saveInfo(code, json)
                    }.let { MariOverApi.parseLevelInfo(code, it) }
                }.getOrNull()
            if (saveLevels) {
                if (cache.data(code) == null) cache.saveData(code, data)
                if (fromArchive == null && cache.thumbnail(code) == null) {
                    MariOverApi.fetchThumbnail(code)?.let { cache.saveThumbnail(code, it) }
                }
                savedLevels = cache.list()
            }

            busyMessage = "Writing to slot $slot…"
            val folder = SaveFolder(location!!)
            if (fileName in folder.list()) {
                val oldData = folder.readFile(fileName)
                store.createBackup("$emulatorName · slot $slot before injecting ${MariOverApi.formatCode(code)}") { write -> write(fileName, oldData) }
            }
            folder.writeFile(fileName, data)

            store.recordInjection(location, slot, HistoryEntry(code, info?.name ?: code, System.currentTimeMillis()))
            history = store.loadHistory(location)
            backups = store.listBackups()
            saveFileNames = saveFileNames + fileName
            val where = if (slot < MY_COURSES_SLOTS) "My Courses" else "Downloaded Courses"
            dialog = DialogState.Success(
                "\"${info?.name ?: code}\" is now in slot $slot ($where).\n\n" +
                    "Open Super Mario Maker 2 in $emulatorName and go to Coursebot to find it."
            )
        }
    }

    // ---------------------------------------------------------------- pre-loaded save

    fun installPreloadedSave(confirmedRunning: Boolean = false) {
        dialog = null
        val location = saveLocation ?: return showError("Choose your emulator and its Super Mario Maker 2 save first.")
        if (!confirmedRunning) return whenEmulatorClosed { installPreloadedSave(confirmedRunning = true) }
        runTask("Preparing…") {
            // Download everything first so a dropped connection never leaves a half-written save.
            val staging = File(appDir, "preloaded-download").apply { deleteRecursively(); mkdirs() }
            try {
                val names = AppStore.PRELOADED_SAVE_FILES
                val done = AtomicInteger()
                val limit = Semaphore(6)
                kotlinx.coroutines.coroutineScope {
                    names.map { name ->
                        async(Dispatchers.IO) {
                            limit.withPermit {
                                store.downloadPreloadedFile(name, File(staging, name))
                                busyMessage = "Downloading pre-loaded save… ${done.incrementAndGet()}/${names.size}"
                            }
                        }
                    }.awaitAll()
                }

                val folder = SaveFolder(location)
                val existingFiles = folder.list()
                if (existingFiles.isNotEmpty()) {
                    store.createBackup("$emulatorName · full save before installing the pre-loaded save") { write ->
                        existingFiles.forEachIndexed { i, name ->
                            busyMessage = "Backing up your current save… ${i + 1}/${existingFiles.size}"
                            write(name, folder.readFile(name))
                        }
                    }
                }
                names.forEachIndexed { i, name ->
                    busyMessage = "Installing pre-loaded save… ${i + 1}/${names.size}"
                    folder.writeFile(name, File(staging, name).readBytes())
                }
            } finally {
                staging.deleteRecursively()
            }
            store.clearHistory(location)
            history = emptyMap()
            backups = store.listBackups()
            refreshSaveFiles()
            dialog = DialogState.Success(
                "The pre-loaded save is installed. All 120 My Courses slots and 60 Downloaded " +
                    "Courses slots now have a course in them, so you can inject into any slot.\n\n" +
                    "Your previous save was backed up and can be restored from the Backups tab."
            )
        }
    }

    // ---------------------------------------------------------------- backups

    fun restoreBackup(backup: Backup, confirmedRunning: Boolean = false) {
        dialog = null
        val location = saveLocation ?: return showError("Choose your emulator and its Super Mario Maker 2 save first.")
        if (!confirmedRunning) return whenEmulatorClosed { restoreBackup(backup, confirmedRunning = true) }
        runTask("Restoring backup…") {
            val folder = SaveFolder(location)
            val files = store.backupFiles(backup)
            files.forEachIndexed { i, file ->
                busyMessage = "Restoring backup… ${i + 1}/${files.size}"
                folder.writeFile(file.name, file.readBytes())
            }
            refreshSaveFiles()
            dialog = DialogState.Success("Restored ${files.size} file(s) from \"${backup.label}\".")
        }
    }

    fun deleteBackup(backup: Backup) {
        store.deleteBackup(backup)
        backups = store.listBackups()
    }

    // ---------------------------------------------------------------- helpers

    private fun showError(message: String) {
        dialog = DialogState.Error(message)
    }

    private fun runTask(initialMessage: String, block: suspend () -> Unit) {
        if (busyMessage != null) return
        busyMessage = initialMessage
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                dialog = DialogState.Error(e.message ?: e.toString())
            } finally {
                busyMessage = null
            }
        }
    }
}
