package io.github.daniel_geron.smm2injector

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.daniel_geron.smm2injector.archive.ArchiveLevel
import io.github.daniel_geron.smm2injector.archive.ArchiveQuery
import io.github.daniel_geron.smm2injector.archive.ArchiveService
import io.github.daniel_geron.smm2injector.archive.LevelNames
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(vm: ArchiveViewModel, mainVm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val job by ArchiveService.state.collectAsState()
    val running = job.task != null && !job.finished
    val listState = rememberLazyListState()
    val status = vm.status

    // Load the next page when the list nears its end.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd, vm.results.size) { if (nearEnd && vm.results.isNotEmpty()) vm.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Level archive") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ArchiveIntro() }
            vm.statusError?.let { err ->
                item {
                    Text(err, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::refreshStatus) { Text("Try again") }
                }
            }
            if (running || job.error != null || (job.finished && job.message.startsWith("Paused"))) {
                item { JobCard(job, onStop = vm::stop) }
            }
            if (status != null) {
                item { SetupCard(vm, status, running) }
            }
            if (status != null && status.indexedLevels > 0) {
                item { FiltersCard(vm) }
                if (!status.indexComplete) {
                    item {
                        Text(
                            "The index is still being built, so results only include levels indexed so far " +
                                "and sorting can be slow.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(vm.results, key = { it.dataId }) { level ->
                    LevelRow(level) { vm.select(level) }
                }
                item {
                    when {
                        vm.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                        }
                        vm.searchError != null -> Text("Search failed: ${vm.searchError}", color = MaterialTheme.colorScheme.error)
                        vm.results.isEmpty() -> Text("No levels match these filters.")
                        vm.endReached -> Text("End of results.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    vm.selected?.let { level -> LevelDetailDialog(level, vm, mainVm) }
}

@Composable
private fun ArchiveIntro() {
    Text(
        "Browse 26.6 million Super Mario Maker 2 levels uploaded up to February 2022, without the level " +
            "server and without needing a code. The archive is TheGreatRambler's mm2_level dataset on " +
            "Hugging Face (CC BY-NC-SA 4.0). It has no thumbnails or maker names.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun JobCard(job: io.github.daniel_geron.smm2injector.archive.ArchiveJobState, onStop: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.error != null) {
                Text("Stopped with an error: ${job.error}", color = MaterialTheme.colorScheme.error)
                Text("Start it again to continue where it left off.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text(job.message, fontWeight = FontWeight.Medium)
            if (!job.finished) {
                val fraction = if (job.total > 0) job.done.toFloat() / job.total else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    when (job.task) {
                        ArchiveService.Task.DOWNLOAD ->
                            "${size(context, job.done)} of ${size(context, job.total)} (${(fraction * 100).roundToInt()}%)"
                        else -> "%,d of %,d levels (%d%%)".format(job.done, job.total, (fraction * 100).roundToInt())
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "This keeps running in the background (see the notification). You can stop it any time and " +
                        "it'll continue where it left off.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun SetupCard(vm: ArchiveViewModel, status: ArchiveStatus, running: Boolean) {
    val context = LocalContext.current
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            if (status.volumes.size > 1) {
                status.volumes.forEachIndexed { i, dir ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !running) { vm.setRoot(dir) },
                    ) {
                        RadioButton(selected = dir == status.root, onClick = { vm.setRoot(dir) }, enabled = !running)
                        Text(
                            (if (i == 0) "Internal storage" else "SD card / external storage $i") +
                                " · ${size(context, dir.freeSpace)} free",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    "Changing storage starts a new, empty archive there.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("${size(context, status.freeBytes)} free on this device.", style = MaterialTheme.typography.bodySmall)
            }

            Text("1. Level index (needed to browse)", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    status.indexComplete -> "Ready: %,d levels indexed (%s).".format(status.indexedLevels, size(context, status.indexBytes))
                    status.indexedLevels > 0 -> "%,d of %,d levels indexed (%s so far).".format(
                        status.indexedLevels, ArchiveService.TOTAL_LEVELS, size(context, status.indexBytes),
                    )
                    else -> "Downloads about 2 GB of level details (names, styles, tags, likes…) and needs about " +
                        "4 GB of space. Levels themselves are fetched one at a time when you inject them."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!status.indexComplete) {
                Button(enabled = !running, onClick = { ensureNotifications(); vm.startIndex() }) {
                    Text(if (status.indexedLevels > 0) "Continue building the index" else "Build the level index")
                }
            }

            Text("2. Full archive (optional, for offline use)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Downloads all ${status.totalFiles} archive files (${size(context, status.totalBytes)}) so levels can " +
                    "be injected with no internet at all. Downloaded: ${status.downloadedFiles} of ${status.totalFiles} " +
                    "files (${size(context, status.downloadedBytes)}).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.downloadedFiles < status.totalFiles) {
                    OutlinedButton(enabled = !running, onClick = { confirmDownload = true }) {
                        Text(if (status.downloadedFiles > 0) "Continue download" else "Download full archive")
                    }
                }
                if (status.downloadedBytes > 0 || status.indexedLevels > 0) {
                    TextButton(enabled = !running, onClick = { confirmDelete = true }) { Text("Delete…") }
                }
            }
        }
    }

    if (confirmDownload) {
        val remaining = status.totalBytes - status.downloadedBytes
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text("Download the full archive?") },
            text = {
                Text(
                    "This downloads ${size(context, remaining)} more. You have ${size(context, status.freeBytes)} free " +
                        "on the selected storage. Use Wi-Fi; it can take many hours, runs in the background and can " +
                        "be stopped and resumed at any time." +
                        if (remaining > status.freeBytes) "\n\n⚠ There isn't enough free space for all of it." else ""
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDownload = false; ensureNotifications(); vm.startDownload() }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete archive data") },
            text = { Text("Delete only the downloaded archive files, or everything including the level index?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteEverything() }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteDownloads() }) { Text("Only downloads") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FiltersCard(vm: ArchiveViewModel) {
    val q = vm.query
    val focus = LocalFocusManager.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = q.search,
                onValueChange = { vm.updateQuery(q.copy(search = it), debounce = true) },
                label = { Text("Search level names") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Sort", ArchiveQuery.Sort.entries.map { it.label }, q.sort.ordinal, allowNone = false) {
                    vm.updateQuery(q.copy(sort = ArchiveQuery.Sort.entries[it!!]))
                }
                ChoiceChip("Style", LevelNames.styles, q.style) { vm.updateQuery(q.copy(style = it)) }
                ChoiceChip("Theme", LevelNames.themes, q.theme) { vm.updateQuery(q.copy(theme = it)) }
                ChoiceChip("Difficulty", LevelNames.difficulties, q.difficulty) { vm.updateQuery(q.copy(difficulty = it)) }
                ChoiceChip("Tag", LevelNames.tags, q.tag) { vm.updateQuery(q.copy(tag = it)) }
            }
            Text(
                "Clear rate: ${q.minClearRate.roundToInt()}% – ${q.maxClearRate.roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
            var range by remember(q.minClearRate, q.maxClearRate) { mutableStateOf(q.minClearRate..q.maxClearRate) }
            RangeSlider(
                value = range,
                onValueChange = { range = it },
                onValueChangeFinished = { vm.updateQuery(q.copy(minClearRate = range.start, maxClearRate = range.endInclusive)) },
                valueRange = 0f..100f,
            )
        }
    }
}

/** A chip that opens a menu of [options]; null selection means "any". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceChip(label: String, options: List<String>, selected: Int?, allowNone: Boolean = true, onSelect: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null && allowNone,
            onClick = { open = true },
            label = { Text(if (selected == null) label else if (allowNone) "$label: ${options[selected]}" else options[selected]) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone) DropdownMenuItem(text = { Text("Any") }, onClick = { open = false; onSelect(null) })
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

@Composable
private fun LevelRow(level: ArchiveLevel, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(level.name.ifBlank { "(no name)" }, fontWeight = FontWeight.Medium)
            Text(
                "${MariOverApi.formatCode(level.code)} · ${LevelNames.style(level.style)} · " +
                    "${LevelNames.theme(level.theme)} · ${LevelNames.difficulty(level.difficulty)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "♥ %,d · %,d plays · %.2f%% clear rate · %s".format(level.likes, level.plays, level.clearRate, tags(level)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LevelDetailDialog(level: ArchiveLevel, vm: ArchiveViewModel, mainVm: MainViewModel) {
    val context = LocalContext.current
    var pickSlot by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.select(null) },
        title = { Text(level.name.ifBlank { "(no name)" }) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(MariOverApi.formatCode(level.code), fontWeight = FontWeight.Medium)
                Text("${LevelNames.style(level.style)} · ${LevelNames.theme(level.theme)} · ${LevelNames.difficulty(level.difficulty)}")
                Text("Tags: ${tags(level)}")
                Text("♥ %,d likes · %,d boos · %,d plays".format(level.likes, level.boos, level.plays))
                Text("%,d clears of %,d attempts (%.2f%%)".format(level.clears, level.attempts, level.clearRate))
                if (level.worldRecordMs > 0) Text("World record: %.3f s".format(level.worldRecordMs / 1000.0))
                if (level.uploaded > 0) {
                    Text("Uploaded ${DateFormat.getDateInstance().format(Date(level.uploaded * 1000))}")
                }
                Text(
                    vm.selectedDescription ?: "Loading description…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { pickSlot = true }) { Text("Inject…") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Level code", MariOverApi.formatCode(level.code)))
                }) { Text("Copy code") }
                TextButton(onClick = { vm.select(null) }) { Text("Close") }
            }
        },
    )
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

private fun tags(level: ArchiveLevel): String =
    listOf(level.tag1, level.tag2).filter { it != 0 }.distinct().joinToString(", ") { LevelNames.tag(it) }.ifEmpty { "no tags" }

private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
