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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface DialogState {
    data class Error(val message: String) : DialogState
    data class Success(val message: String) : DialogState
    data class ConfirmEmptySlot(val slot: Int) : DialogState
    data object ConfirmPreloaded : DialogState
    data class ConfirmRestore(val backup: Backup) : DialogState
    data class ChooseSave(val candidates: List<SaveLocation>) : DialogState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppStore(app)
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
        runTask("Looking up $code…") {
            levelInfo = MariOverApi.fetchLevelInfo(code)
            thumbnail = MariOverApi.fetchThumbnail(code)
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?.asImageBitmap()
        }
    }

    // ---------------------------------------------------------------- inject

    fun parsedSlot(): Int? = slotInput.trim().toIntOrNull()?.takeIf { it in 0 until TOTAL_SLOTS }

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

        runTask("Downloading $code…") {
            val data = MariOverApi.fetchLevelData(code)
            val info = levelInfo?.takeIf { it.code == code }
                ?: runCatching { MariOverApi.fetchLevelInfo(code) }.getOrNull()

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
