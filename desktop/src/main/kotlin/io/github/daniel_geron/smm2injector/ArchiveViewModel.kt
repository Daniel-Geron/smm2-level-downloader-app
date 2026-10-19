package io.github.daniel_geron.smm2injector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.daniel_geron.smm2injector.archive.ArchiveLevel
import io.github.daniel_geron.smm2injector.archive.ArchiveQuery
import io.github.daniel_geron.smm2injector.archive.ArchiveJobs
import io.github.daniel_geron.smm2injector.archive.LevelArchive
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ArchiveStatus(
    val indexedLevels: Long,
    val indexComplete: Boolean,
    val indexBytes: Long,
    val downloadedFiles: Int,
    val totalFiles: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val freeBytes: Long,
    val dir: File,
    val isDefaultDir: Boolean,
)

class ArchiveViewModel(private val archive: LevelArchive) {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var status by mutableStateOf<ArchiveStatus?>(null)
        private set
    var statusError by mutableStateOf<String?>(null)
        private set

    var query by mutableStateOf(ArchiveQuery())
        private set
    var results by mutableStateOf<List<ArchiveLevel>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set

    var selected by mutableStateOf<ArchiveLevel?>(null)
        private set
    var selectedDescription by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null

    init {
        refreshStatus()
        viewModelScope.launch {
            var wasRunning = false
            ArchiveJobs.state.collect { s ->
                val running = s.task != null && !s.finished
                if (wasRunning && !running) {
                    refreshStatus()
                    search()
                }
                wasRunning = running
            }
        }
        // While a job runs, refresh the numbers now and then.
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                val s = ArchiveJobs.state.value
                if (s.task != null && !s.finished) refreshStatus()
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            try {
                status = withContext(Dispatchers.IO) {
                    val shards = archive.shards()
                    ArchiveStatus(
                        indexedLevels = archive.indexedLevelCount(),
                        indexComplete = shards.all { it.indexed },
                        indexBytes = archive.indexBytes(),
                        downloadedFiles = shards.count { it.downloaded },
                        totalFiles = shards.size,
                        downloadedBytes = archive.downloadedBytes(),
                        totalBytes = shards.sumOf { it.size },
                        freeBytes = archive.freeBytes(),
                        dir = archive.archiveDir,
                        isDefaultDir = archive.archiveDir == archive.defaultDir,
                    )
                }
                statusError = null
                if (results.isEmpty() && (status?.indexedLevels ?: 0) > 0) search()
            } catch (e: Exception) {
                statusError = "Couldn't load the archive's file list: ${e.message}. Check your internet connection."
            }
        }
    }

    fun updateQuery(q: ArchiveQuery, debounce: Boolean = false) {
        query = q
        search(if (debounce) 400 else 0)
    }

    fun search(delayMs: Long = 0) {
        searchJob?.cancel()
        results = emptyList()
        endReached = false
        searchJob = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            loadPage()
        }
    }

    fun loadMore() {
        if (loading || endReached) return
        searchJob = viewModelScope.launch { loadPage() }
    }

    private suspend fun loadPage() {
        loading = true
        try {
            val page = withContext(Dispatchers.IO) { archive.search(query, results.size, PAGE_SIZE) }
            results = results + page
            endReached = page.size < PAGE_SIZE
            searchError = null
        } catch (e: Exception) {
            searchError = e.message
            endReached = true
        } finally {
            loading = false
        }
    }

    fun select(level: ArchiveLevel?) {
        selected = level
        selectedDescription = null
        if (level == null) return
        viewModelScope.launch {
            selectedDescription = withContext(Dispatchers.IO) {
                runCatching { archive.description(level.dataId) ?: "" }
                    .getOrElse { "(Couldn't load the description: ${it.message})" }
            }
        }
    }

    fun startIndex() = ArchiveJobs.start(archive, ArchiveJobs.Task.INDEX)

    fun startDownload() = ArchiveJobs.start(archive, ArchiveJobs.Task.DOWNLOAD)

    fun stop() = ArchiveJobs.stop()

    /** Moves to the archive in (or a new one under) the folder the user picked. */
    fun setFolder(dir: File?) {
        archive.archiveDir = dir?.let(archive::archiveDirFor) ?: archive.defaultDir
        results = emptyList()
        refreshStatus()
    }

    fun deleteDownloads() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { archive.deleteDownloads() }
            refreshStatus()
        }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { archive.deleteEverything() }
            results = emptyList()
            refreshStatus()
        }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}
