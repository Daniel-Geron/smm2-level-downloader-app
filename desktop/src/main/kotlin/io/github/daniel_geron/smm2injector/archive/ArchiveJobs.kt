package io.github.daniel_geron.smm2injector.archive

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Progress of the current archive job, observed by the UI. */
data class ArchiveJobState(
    val task: ArchiveJobs.Task? = null,
    val done: Long = 0,
    val total: Long = 0,
    val message: String = "",
    val error: String? = null,
    val finished: Boolean = false,
)

/**
 * Runs the long archive jobs (building the index, downloading the full archive) in the background
 * while the window stays usable. They stop when the app closes and continue where they left off.
 */
object ArchiveJobs {
    enum class Task { INDEX, DOWNLOAD }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopped = false
    private lateinit var archive: LevelArchive

    /** Number of levels in the archive, for progress. */
    const val TOTAL_LEVELS = 26_609_725L
    private const val PARALLEL_FILES = 3

    private val _state = MutableStateFlow(ArchiveJobState())
    val state: StateFlow<ArchiveJobState> = _state

    @Synchronized
    fun start(archive: LevelArchive, task: Task) {
        if (job?.isActive == true) return
        this.archive = archive
        stopped = false
        _state.value = ArchiveJobState(task = task, message = "Starting…")
        job = scope.launch {
            try {
                when (task) {
                    Task.INDEX -> buildIndex()
                    Task.DOWNLOAD -> downloadAll()
                }
                if (!stopped) finish(null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stopped by the user
            } catch (e: Exception) {
                if (!stopped) finish(e.message ?: e.toString())
            }
        }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        _state.value = _state.value.copy(finished = true, message = "Stopped")
    }

    private suspend fun buildIndex() {
        val shards = archive.shards()
        val done = AtomicLong(archive.indexedLevelCount())
        val total = TOTAL_LEVELS
        val dbLock = Mutex()
        report(Task.INDEX, done.get(), total, "Building level index…")

        // A few files at a time: downloads overlap, inserts into the database take turns.
        val queue = shards.filter { !it.indexed }.toMutableList()
        coroutineScope {
            repeat(PARALLEL_FILES) {
                launch {
                    while (isActive) {
                        val shard = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                        for (rg in archive.pendingGroups(shard)) {
                            if (!isActive) return@launch
                            val cols = archive.fetchGroup(shard, rg)
                            val added = dbLock.withLock { archive.insertGroup(shard, rg, cols) }
                            report(Task.INDEX, done.addAndGet(added.toLong()), total, "Building level index…")
                        }
                    }
                }
            }
        }
        if (stopped) return
        report(Task.INDEX, done.get(), total, "Finishing up (sorting the index)…")
        archive.finishIndex()
    }

    private suspend fun downloadAll() {
        val shards = archive.shards()
        val total = shards.sumOf { it.size }
        var completed = shards.filter { it.downloaded }.sumOf { it.size }
        for (shard in shards.filter { !it.downloaded }) {
            if (stopped) return
            archive.download(shard, isCancelled = { stopped }) { have ->
                report(Task.DOWNLOAD, completed + have, total, "Downloading the full archive…")
            }
            completed += shard.size
        }
    }

    private fun report(task: Task, done: Long, total: Long, message: String) {
        if (stopped) return
        _state.value = ArchiveJobState(task, done, total, message)
    }

    private fun finish(error: String?) {
        val s = _state.value
        _state.value = s.copy(error = error, finished = true, message = error ?: "Done")
    }
}
