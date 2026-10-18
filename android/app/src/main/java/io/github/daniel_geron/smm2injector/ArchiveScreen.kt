package io.github.daniel_geron.smm2injector

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.daniel_geron.smm2injector.archive.ArchiveJobState
import io.github.daniel_geron.smm2injector.archive.ArchiveLevel
import io.github.daniel_geron.smm2injector.archive.ArchiveQuery
import io.github.daniel_geron.smm2injector.archive.ArchiveService
import io.github.daniel_geron.smm2injector.archive.LevelNames
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun ArchiveTab(vm: ArchiveViewModel, mainVm: MainViewModel) {
    val job by ArchiveService.state.collectAsState()
    val running = job.task != null && !job.finished
    val listState = rememberLazyListState()
    val status = vm.status
    var showFilters by remember { mutableStateOf(false) }

    // Load the next page when the list nears its end.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, vm.results.size) { if (nearEnd && vm.results.isNotEmpty()) vm.loadMore() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        vm.statusError?.let { err ->
            item {
                SmmCard(Modifier.fillMaxWidth()) {
                    Text(err, color = Smm.Red)
                    SmmButton("Try again", small = true, onClick = vm::refreshStatus)
                }
            }
        }
        if (running || job.error != null || (job.finished && job.message.startsWith("Paused"))) {
            item { JobCard(job, onStop = vm::stop) }
        }
        if (status != null) {
            item { SetupCard(vm, status, running) }
        }
        if (status != null && status.indexedLevels > 0) {
            item { SearchBar(vm, onFilters = { showFilters = true }) }
            if (!status.indexComplete) {
                item {
                    SmallNote(
                        "The index is still being built, so results only include levels indexed so far " +
                            "and sorting can be slow."
                    )
                }
            }
            items(vm.results, key = { it.dataId }) { level ->
                CourseCard(level) { vm.select(level) }
            }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        vm.loading -> CircularProgressIndicator(Modifier.size(32.dp), color = Smm.Orange, trackColor = Smm.Beige)
                        vm.searchError != null -> Text("Search failed: ${vm.searchError}", color = Smm.Red)
                        vm.results.isEmpty() -> Text("No levels match these filters.")
                        vm.endReached -> SmallNote("End of results.")
                    }
                }
            }
        }
    }

    if (showFilters) FilterDialog(vm, onDismiss = { showFilters = false })
    vm.selected?.let { level -> LevelDetailDialog(level, vm, mainVm) }
}

@Composable
private fun JobCard(job: ArchiveJobState, onStop: () -> Unit) {
    val context = LocalContext.current
    SmmCard(Modifier.fillMaxWidth()) {
        if (job.error != null) {
            Text("Stopped with an error: ${job.error}", color = Smm.Red)
            SmallNote("Start it again to continue where it left off.")
            return@SmmCard
        }
        Text(job.message, style = MaterialTheme.typography.titleMedium)
        if (!job.finished) {
            val fraction = if (job.total > 0) job.done.toFloat() / job.total else 0f
            SmmProgressBar(fraction)
            Text(
                when (job.task) {
                    ArchiveService.Task.DOWNLOAD ->
                        "${size(context, job.done)} of ${size(context, job.total)} (${(fraction * 100).roundToInt()}%)"
                    else -> "%,d of %,d levels (%d%%)".format(job.done, job.total, (fraction * 100).roundToInt())
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            SmallNote(
                "This keeps running in the background (see the notification). You can stop it any time and " +
                    "it'll continue where it left off."
            )
            SmmButton("Stop", small = true, style = SmmButtonStyle.White, onClick = onStop)
        }
    }
}

@Composable
private fun SetupCard(vm: ArchiveViewModel, status: ArchiveStatus, running: Boolean) {
    val context = LocalContext.current
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Once there's something to browse, the setup is folded away under its summary line.
    var expanded by remember { mutableStateOf(status.indexedLevels == 0L) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    SmmCard(Modifier.fillMaxWidth(), onClick = if (status.indexedLevels > 0) ({ expanded = !expanded }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Level archive", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (status.indexedLevels > 0) SmallNote(if (expanded) "Hide ▲" else "Manage ▼")
        }
        SmallNote(
            "26.6 million levels uploaded up to February 2022, from TheGreatRambler's mm2_level dataset " +
                "on Hugging Face (CC BY-NC-SA 4.0). No code or level server needed. The archive has no " +
                "thumbnails or maker names."
        )
        if (!expanded) {
            Text(
                if (status.indexComplete) "Index ready: %,d levels".format(status.indexedLevels)
                else "Index: %,d of %,d levels".format(status.indexedLevels, ArchiveService.TOTAL_LEVELS),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SmmCard
        }

        Text("Storage", style = MaterialTheme.typography.titleMedium)
        if (status.volumes.size > 1) {
            FlowRowCompat {
                status.volumes.forEachIndexed { i, dir ->
                    SmmChip(
                        (if (i == 0) "Internal storage" else "SD card $i") + " · ${size(context, dir.freeSpace)} free",
                        selected = dir == status.root,
                        onClick = { if (!running) vm.setRoot(dir) },
                    )
                }
            }
            SmallNote("Changing storage starts a new, empty archive there.")
        } else {
            SmallNote("${size(context, status.freeBytes)} free on this device.")
        }

        Text("1. Level index (needed to browse)", style = MaterialTheme.typography.titleMedium)
        SmallNote(
            when {
                status.indexComplete -> "Ready: %,d levels indexed (%s).".format(status.indexedLevels, size(context, status.indexBytes))
                status.indexedLevels > 0 -> "%,d of %,d levels indexed (%s so far).".format(
                    status.indexedLevels, ArchiveService.TOTAL_LEVELS, size(context, status.indexBytes),
                )
                else -> "Downloads about 2 GB of level details (names, styles, tags, likes…) and needs about " +
                    "4 GB of space. Levels themselves are fetched one at a time when you inject them."
            }
        )
        if (!status.indexComplete) {
            SmmButton(
                if (status.indexedLevels > 0) "Continue building the index" else "Build the level index",
                enabled = !running,
                highlighted = status.indexedLevels == 0L && !running,
                onClick = { ensureNotifications(); vm.startIndex() },
            )
        }

        Text("2. Full archive (optional, for offline use)", style = MaterialTheme.typography.titleMedium)
        SmallNote(
            "Downloads all ${status.totalFiles} archive files (${size(context, status.totalBytes)}) so levels can " +
                "be injected with no internet at all. Downloaded: ${status.downloadedFiles} of ${status.totalFiles} " +
                "files (${size(context, status.downloadedBytes)})."
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.downloadedFiles < status.totalFiles) {
                SmmButton(
                    if (status.downloadedFiles > 0) "Continue download" else "Download full archive",
                    small = true,
                    style = SmmButtonStyle.White,
                    enabled = !running,
                    onClick = { confirmDownload = true },
                )
            }
            if (status.downloadedBytes > 0 || status.indexedLevels > 0) {
                SmmButton(
                    "Delete…",
                    small = true,
                    style = SmmButtonStyle.White,
                    enabled = !running,
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDownload) {
        val remaining = status.totalBytes - status.downloadedBytes
        ConfirmDialog(
            title = "Download the full archive?",
            text = "This downloads ${size(context, remaining)} more. You have ${size(context, status.freeBytes)} free " +
                "on the selected storage. Use Wi-Fi; it can take many hours, runs in the background and can " +
                "be stopped and resumed at any time." +
                if (remaining > status.freeBytes) "\n\n⚠ There isn't enough free space for all of it." else "",
            confirm = "Download",
            onConfirm = { confirmDownload = false; ensureNotifications(); vm.startDownload() },
            onDismiss = { confirmDownload = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete archive data",
            text = "Delete only the downloaded archive files, or everything including the level index?",
            confirm = "Delete everything",
            onConfirm = { confirmDelete = false; vm.deleteEverything() },
            dismiss = "Only downloads",
            onDismissButton = { confirmDelete = false; vm.deleteDownloads() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Name search plus a summary of the active filters, which open the game-style search dialog. */
@Composable
private fun SearchBar(vm: ArchiveViewModel, onFilters: () -> Unit) {
    val q = vm.query
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmmTextField(
                value = q.search,
                onValueChange = { vm.updateQuery(q.copy(search = it), debounce = true) },
                placeholder = "Search level names",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.weight(1f),
            )
            SmmButton("Filters", icon = Icons.Filled.Search, onClick = onFilters)
        }
        val active = buildList {
            add(q.sort.label)
            q.style?.let { add(LevelNames.style(it)) }
            q.theme?.let { add(LevelNames.theme(it)) }
            q.difficulty?.let { add(LevelNames.difficulty(it)) }
            q.tag?.let { add(LevelNames.tag(it)) }
            if (q.minClearRate > 0f || q.maxClearRate < 100f) {
                add("${q.minClearRate.roundToInt()}–${q.maxClearRate.roundToInt()}% clear rate")
            }
        }
        SmallNote(active.joinToString(" · "))
    }
}

/** The game's course search panel: arrow selectors, tag toggles and the sort order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(vm: ArchiveViewModel, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(vm.query) }
    SmmDialog(
        onDismissRequest = onDismiss,
        title = "Search options",
        onClose = onDismiss,
        buttons = {
            SmmButton(
                "Reset",
                style = SmmButtonStyle.White,
                modifier = Modifier.weight(1f),
                onClick = { draft = ArchiveQuery(search = draft.search) },
            )
            SmmButton("Search", highlighted = true, modifier = Modifier.weight(1f), onClick = {
                vm.updateQuery(draft.copy(search = vm.query.search))
                onDismiss()
            })
        },
    ) {
        SmmSelector("Sort", ArchiveQuery.Sort.entries.map { it.label }, draft.sort.ordinal, allowAll = false) {
            draft = draft.copy(sort = ArchiveQuery.Sort.entries[it!!])
        }
        SmmSelector("Style", LevelNames.styles, draft.style) { draft = draft.copy(style = it) }
        SmmSelector("Theme", LevelNames.themes, draft.theme) { draft = draft.copy(theme = it) }
        SmmSelector("Difficulty", LevelNames.difficulties, draft.difficulty) { draft = draft.copy(difficulty = it) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tag", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            SmmChip("All", selected = draft.tag == null, onClick = { draft = draft.copy(tag = null) })
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LevelNames.tags.forEachIndexed { i, tag ->
                SmmChip(tag, selected = draft.tag == i, onClick = { draft = draft.copy(tag = if (draft.tag == i) null else i) })
            }
        }

        Text(
            "Clear rate: ${draft.minClearRate.roundToInt()}% – ${draft.maxClearRate.roundToInt()}%",
            style = MaterialTheme.typography.titleMedium,
        )
        RangeSlider(
            value = draft.minClearRate..draft.maxClearRate,
            onValueChange = { draft = draft.copy(minClearRate = it.start, maxClearRate = it.endInclusive) },
            valueRange = 0f..100f,
            colors = smmSliderColors(),
        )
    }
}

/** A level in the results, laid out like a course in the game's lists. */
@Composable
private fun CourseCard(level: ArchiveLevel, onClick: () -> Unit) {
    SmmCard(Modifier.fillMaxWidth(), onClick = onClick, padding = 10.dp, spacing = 8.dp) {
        CourseTitle(styleBadge = { StyleBadge(level.style) }, name = level.name)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeThumbnail(
                level.theme,
                uploadDate(level),
                Modifier
                    .width(132.dp)
                    .aspectRatio(16f / 9f),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeartStat(level.likes)
                PlaysStat(level.plays)
                SmallNote("${LevelNames.difficulty(level.difficulty)} · %.2f%% clears".format(level.clearRate))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TagLabel(tagName(level.tag1))
            TagLabel(tagName(level.tag2).takeIf { level.tag2 != level.tag1 })
        }
    }
}

/** An opened course: stats, description and the cells under it, with the inject button. */
@Composable
private fun LevelDetailDialog(level: ArchiveLevel, vm: ArchiveViewModel, mainVm: MainViewModel) {
    val context = LocalContext.current
    var pickSlot by remember { mutableStateOf(false) }
    SmmDialog(
        onDismissRequest = { vm.select(null) },
        header = { CourseTitle(styleBadge = { StyleBadge(level.style) }, name = level.name) },
        onClose = { vm.select(null) },
        buttons = {
            SmmButton("Copy ID", style = SmmButtonStyle.White, modifier = Modifier.weight(1f), onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Level code", MariOverApi.formatCode(level.code)))
            })
            SmmButton("Inject…", highlighted = true, modifier = Modifier.weight(1f), onClick = { pickSlot = true })
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeThumbnail(
                level.theme,
                uploadDate(level),
                Modifier
                    .width(140.dp)
                    .aspectRatio(16f / 9f),
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                HeartStat(level.likes)
                PlaysStat(level.plays)
                TagLabel(tagName(level.tag1))
                TagLabel(tagName(level.tag2).takeIf { level.tag2 != level.tag1 })
            }
        }
        DescriptionBox(vm.selectedDescription?.ifBlank { "(no description)" } ?: "Loading description…")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCell("World Record", if (level.worldRecordMs > 0) formatRecord(level.worldRecordMs) else "---")
            InfoCell("Clear Rate", "%.2f%%".format(level.clearRate))
            InfoCell("Course ID", MariOverApi.formatCode(level.code), weight = 1.5f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCell("Difficulty", LevelNames.difficulty(level.difficulty))
            InfoCell("Theme", LevelNames.theme(level.theme))
            InfoCell("Boos", "%,d".format(level.boos))
        }
        SmallNote("%,d clears of %,d attempts".format(level.clears, level.attempts))
    }
    if (pickSlot) {
        SlotPickerDialog(
            vm = mainVm,
            onPick = { slot ->
                pickSlot = false
                vm.select(null)
                mainVm.injectFromArchive(level, slot)
            },
            onDismiss = { pickSlot = false },
        )
    }
}

private fun tagName(tag: Int): String? = if (tag == 0) null else LevelNames.tag(tag)

private fun uploadDate(level: ArchiveLevel): String? =
    if (level.uploaded > 0) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(level.uploaded * 1000)) else null

/** Formats a world record the way the game does: 0'18"399. */
private fun formatRecord(ms: Int): String = "%d'%02d\"%03d".format(ms / 60000, ms / 1000 % 60, ms % 1000)

private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
