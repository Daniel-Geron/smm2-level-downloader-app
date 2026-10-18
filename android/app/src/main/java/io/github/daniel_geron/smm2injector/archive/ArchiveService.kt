package io.github.daniel_geron.smm2injector.archive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.daniel_geron.smm2injector.MainActivity
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Progress of the current archive job, observed by the UI. */
data class ArchiveJobState(
    val task: ArchiveService.Task? = null,
    val done: Long = 0,
    val total: Long = 0,
    val message: String = "",
    val error: String? = null,
    val finished: Boolean = false,
)

/**
 * Runs the long archive jobs (building the index, downloading the full archive) in a foreground
 * service so they keep going while the app is in the background.
 */
class ArchiveService : Service() {
    enum class Task { INDEX, DOWNLOAD }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastNotified = 0L
    @Volatile private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopJob("Stopped")
            return START_NOT_STICKY
        }
        val task = intent?.getStringExtra(EXTRA_TASK)?.let(Task::valueOf) ?: return START_NOT_STICKY
        if (job?.isActive == true) return START_NOT_STICKY

        val notification = buildNotification("Starting…", 0, 0)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smm2injector:archive").apply { acquire(12 * 60 * 60 * 1000L) }

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
        return START_NOT_STICKY
    }

    private suspend fun buildIndex() {
        val archive = LevelArchive.get(this)
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
        val archive = LevelArchive.get(this)
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
        val now = System.currentTimeMillis()
        if (now - lastNotified > 1000) {
            lastNotified = now
            val pct = if (total > 0) (done * 100 / total).toInt() else 0
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification("$message $pct%", pct, 100))
        }
    }

    private fun finish(error: String?) {
        val s = _state.value
        _state.value = s.copy(error = error, finished = true, message = error ?: "Done")
        releaseAndStop()
    }

    private fun stopJob(message: String) {
        stopped = true
        job?.cancel()
        _state.value = _state.value.copy(finished = true, message = message)
        releaseAndStop()
    }

    private fun releaseAndStop() {
        wakeLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Android 15+ limits data-sync services to 6 hours a day; stop cleanly so the user can resume later.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopJob("Paused: Android's daily limit for background downloads was reached. Resume later.")
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Level archive", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ArchiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("SMM2 level archive")
            .setContentText(text)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "archive"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TASK = "task"
        private const val ACTION_STOP = "stop"
        private const val PARALLEL_FILES = 3

        /** Number of levels in the archive, for progress. */
        const val TOTAL_LEVELS = 26_609_725L

        private val _state = MutableStateFlow(ArchiveJobState())
        val state: StateFlow<ArchiveJobState> = _state

        fun start(context: Context, task: Task) {
            ContextCompat.startForegroundService(
                context, Intent(context, ArchiveService::class.java).putExtra(EXTRA_TASK, task.name),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ArchiveService::class.java).setAction(ACTION_STOP))
        }
    }
}
