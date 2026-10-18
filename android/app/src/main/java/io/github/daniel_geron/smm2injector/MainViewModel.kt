package io.github.daniel_geron.smm2injector

import android.app.Application
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.daniel_geron.smm2injector.archive.ArchiveLevel
import io.github.daniel_geron.smm2injector.archive.CourseCode
import io.github.daniel_geron.smm2injector.archive.LevelArchive
import io.github.daniel_geron.smm2injector.archive.LevelNames
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
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
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppStore(app)
    private val cache = LevelCache(app)
    private val archive = LevelArchive.get(app)
    private val resolver = app.contentResolver

    var treeUri by mutableStateOf<Uri?>(null)
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

    var history by mutableStateOf(store.loadHistory())
        private set
    var backups by mutableStateOf(store.listBackups())
        private set

    /** Non-null while a long-running task is in progress; shown as the progress text. */
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var dialog by mutableStateOf<DialogState?>(null)

    /** The Eden build that owns the picked folder, or any installed Eden as a fallback. */
    val edenPackage: String?
        get() = treeUri?.let(::edenPackageForTree) ?: findInstalledEden(getApplication())

    init {
        checkServer()
        val savedTree = store.treeUri
        val stillGranted = savedTree != null &&
            resolver.persistedUriPermissions.any { it.uri == savedTree && it.isWritePermission }
        if (stillGranted) {
            treeUri = savedTree
            saveLocation = store.saveLocation
            if (saveLocation == null) scanForSaves() else refreshSaveFiles()
        }
    }

    // ---------------------------------------------------------------- save folder

    fun onTreePicked(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Drop old grants so we don't pile them up each time the user re-picks.
        resolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach { runCatching { resolver.releasePersistableUriPermission(it.uri, flags) } }
        resolver.takePersistableUriPermission(uri, flags)
        treeUri = uri
        store.treeUri = uri
        setSave(null)
        scanForSaves()
    }

    fun scanForSaves() {
        val uri = treeUri ?: return
        runTask("Looking for Super Mario Maker 2 save data…") {
            val found = SafTree(resolver, uri).findSmm2Saves()
                // Eden prefers the newer "account" layout when it exists.
                .sortedByDescending { "/account/" in "/${it.displayPath}" }
            when {
                found.isEmpty() -> dialog = DialogState.Error(
                    "No Super Mario Maker 2 save was found in that folder.\n\n" +
                        "Start Super Mario Maker 2 in Eden once (get to the title screen so it creates " +
                        "a save), close Eden, then select Eden's folder again."
                )
                found.size == 1 -> setSave(found.single())
                else -> dialog = DialogState.ChooseSave(found)
            }
        }
    }

    fun chooseSave(location: SaveLocation) {
        dialog = null
        setSave(location)
    }

    private fun setSave(location: SaveLocation?) {
        saveLocation = location
        store.saveLocation = location
        saveFileNames = emptySet()
        if (location != null) refreshSaveFiles()
    }

    private fun refreshSaveFiles() {
        val uri = treeUri ?: return
        val location = saveLocation ?: return
        viewModelScope.launch {
            val names = withContext(Dispatchers.IO) {
                runCatching { SafTree(resolver, uri).list(location.docId).map { it.name }.toSet() }
                    .getOrNull()
            }
            if (names == null) {
                // The folder moved or was deleted (e.g. Eden was reinstalled); look again.
                setSave(null)
                scanForSaves()
            } else {
                saveFileNames = names
            }
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
                        busyMessage = "Saving ${MariOverApi.formatCode(code)} on this device…"
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
        thumbnail = jpg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
    }

    private fun notSavedError(e: MariOverApi.ServerDownException) = MariOverApi.ServerDownException(
        e.message + "\n\nThis level isn't saved on your device or in the 2022 level archive, so it can't be " +
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

    fun inject(confirmedEmptySlot: Boolean = false) {
        dialog = null
        val code = MariOverApi.normalizeCode(codeInput)
        val slot = parsedSlot()
        val uri = treeUri
        val location = saveLocation
        when {
            uri == null || location == null ->
                return showError("Select Eden's folder first (step 1).")
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
            val tree = SafTree(resolver, uri!!)
            val files = tree.list(location!!.docId)
            files.firstOrNull { !it.isDir && it.name == fileName }?.let { old ->
                val oldData = tree.readFile(old.id)
                store.createBackup("Slot $slot before injecting $code") { write -> write(fileName, oldData) }
            }
            tree.writeFile(location.docId, files, fileName, data)

            store.recordInjection(slot, HistoryEntry(code, info?.name ?: code, System.currentTimeMillis()))
            history = store.loadHistory()
            backups = store.listBackups()
            saveFileNames = saveFileNames + fileName
            val where = if (slot < MY_COURSES_SLOTS) "My Courses" else "Downloaded Courses"
            dialog = DialogState.Success(
                "\"${info?.name ?: code}\" is now in slot $slot ($where).\n\n" +
                    "Open Super Mario Maker 2 in Eden and go to Coursebot to find it."
            )
        }
    }

    // ---------------------------------------------------------------- pre-loaded save

    fun installPreloadedSave() {
        dialog = null
        val uri = treeUri ?: return showError("Select Eden's folder first (step 1).")
        val location = saveLocation ?: return showError("Select Eden's folder first (step 1).")
        val app = getApplication<Application>()
        runTask("Preparing…") {
            // Download everything first so a dropped connection never leaves a half-written save.
            val staging = File(app.cacheDir, "preloaded").apply { deleteRecursively(); mkdirs() }
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

                val tree = SafTree(resolver, uri)
                val existing = tree.list(location.docId)
                val existingFiles = existing.filter { !it.isDir }
                if (existingFiles.isNotEmpty()) {
                    store.createBackup("Full save before installing the pre-loaded save") { write ->
                        existingFiles.forEachIndexed { i, f ->
                            busyMessage = "Backing up your current save… ${i + 1}/${existingFiles.size}"
                            write(f.name, tree.readFile(f.id))
                        }
                    }
                }
                names.forEachIndexed { i, name ->
                    busyMessage = "Installing pre-loaded save… ${i + 1}/${names.size}"
                    tree.writeFile(location.docId, existing, name, File(staging, name).readBytes())
                }
            } finally {
                staging.deleteRecursively()
            }
            store.clearHistory()
            history = emptyMap()
            backups = store.listBackups()
            refreshSaveFiles()
            dialog = DialogState.Success(
                "The pre-loaded save is installed. All 120 My Courses slots and 60 Downloaded " +
                    "Courses slots now have a course in them, so you can inject into any slot.\n\n" +
                    "Your previous save was backed up and can be restored from the Backups section."
            )
        }
    }

    // ---------------------------------------------------------------- backups

    fun restoreBackup(backup: Backup) {
        dialog = null
        val uri = treeUri ?: return showError("Select Eden's folder first (step 1).")
        val location = saveLocation ?: return showError("Select Eden's folder first (step 1).")
        runTask("Restoring backup…") {
            val tree = SafTree(resolver, uri)
            val existing = tree.list(location.docId)
            val files = store.backupFiles(backup)
            files.forEachIndexed { i, file ->
                busyMessage = "Restoring backup… ${i + 1}/${files.size}"
                tree.writeFile(location.docId, existing, file.name, file.readBytes())
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
