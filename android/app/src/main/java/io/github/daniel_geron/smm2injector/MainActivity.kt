package io.github.daniel_geron.smm2injector

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) {
                    darkColorScheme(primary = Color(0xFFFF8A80), secondary = Color(0xFFFBD000))
                } else {
                    lightColorScheme(primary = Color(0xFFD32F2F), secondary = Color(0xFFB8860B))
                }
            ) {
                MainScreen(viewModel())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val pickFolder = rememberLauncherForActivityResult(OpenEdenFolder()) { uri ->
        if (uri != null) vm.onTreePicked(uri)
    }
    var showSlotPicker by remember { mutableStateOf(false) }
    var showArchive by rememberSaveable { mutableStateOf(false) }
    val archiveVm: ArchiveViewModel = viewModel()
    val busy = vm.busyMessage != null

    Box(Modifier.fillMaxSize()) {
        if (showArchive) ArchiveScreen(archiveVm, vm, onBack = { showArchive = false }) else Scaffold(topBar = { TopAppBar(title = { Text("SMM2 Level Injector") }) }) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ServerStatusRow(vm)
                OutlinedButton(onClick = { showArchive = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Browse the level archive (26.6M levels, no code needed)")
                }

                // ---------------------------------------------------------- step 1
                StepCard("1. Eden save folder") {
                    val location = vm.saveLocation
                    when {
                        vm.treeUri == null -> Text(
                            "Tap the button, then choose \"Eden\" in the side menu of the file picker " +
                                "and press \"Use this folder\" (or \"Select\")."
                        )
                        location == null -> Text("No Super Mario Maker 2 save selected yet.")
                        else -> {
                            Text("Super Mario Maker 2 save found ✓", fontWeight = FontWeight.Bold)
                            Text(location.displayPath, style = MaterialTheme.typography.bodySmall)
                            val filled = (0 until TOTAL_SLOTS).count { courseDataName(it) in vm.saveFileNames }
                            Text(
                                "$filled of $TOTAL_SLOTS course slots have a course file.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                val eden = findInstalledEden(context)
                                pickFolder.launch(eden?.let(::edenRootUri))
                            },
                        ) { Text(if (vm.treeUri == null) "Select Eden folder" else "Change folder") }
                        if (vm.treeUri != null) {
                            OutlinedButton(enabled = !busy, onClick = vm::scanForSaves) { Text("Rescan") }
                        }
                    }
                }

                // ---------------------------------------------------------- step 2
                StepCard("2. Level code") {
                    OutlinedTextField(
                        value = vm.codeInput,
                        // Only letters/numbers are kept; the dashes are drawn by CodeDashes.
                        onValueChange = { vm.codeInput = MariOverApi.normalizeCode(it).take(9) },
                        label = { Text("Level code (e.g. ABC-DEF-GHI)") },
                        visualTransformation = CodeDashes,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            vm.lookUpLevel()
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            focusManager.clearFocus()
                            vm.lookUpLevel()
                        },
                    ) { Text("Preview level") }
                    vm.levelInfo?.let { info -> LevelPreview(info, vm) }
                }

                // ---------------------------------------------------------- step 3
                StepCard("3. Slot to overwrite") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = vm.slotInput,
                            onValueChange = { vm.slotInput = it.filter(Char::isDigit).take(3) },
                            label = { Text("Slot (0–179)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.width(140.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { showSlotPicker = true }) { Text("Pick…") }
                    }
                    SlotStatus(vm)
                }

                // ---------------------------------------------------------- step 4
                Button(
                    enabled = !busy,
                    onClick = {
                        focusManager.clearFocus()
                        vm.inject()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                ) { Text("Download & inject level", style = MaterialTheme.typography.titleMedium) }
                Text(
                    "⚠ Close Eden completely (swipe it away from recent apps) before injecting, " +
                        "otherwise the game can overwrite the new level when it saves.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val eden = vm.edenPackage
                if (eden != null) {
                    OutlinedButton(
                        onClick = {
                            context.packageManager.getLaunchIntentForPackage(eden)?.let(context::startActivity)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open Eden") }
                }

                // ---------------------------------------------------------- extras
                StepCard("Pre-loaded save (optional)") {
                    Text(
                        "Super Mario Maker 2 ignores injected levels in slots that are empty in your save. " +
                            "If you don't want to fill slots by hand, install the pre-loaded save, which has " +
                            "every My Courses and Downloaded Courses slot filled. It replaces your SMM2 save; " +
                            "your current one is backed up first.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        enabled = !busy && vm.saveLocation != null,
                        onClick = { vm.dialog = DialogState.ConfirmPreloaded },
                    ) { Text("Install pre-loaded save (~135 MB)") }
                }

                SavedLevelsCard(vm, busy)

                if (vm.backups.isNotEmpty()) {
                    StepCard("Backups") {
                        Text(
                            "Files are backed up automatically before they're overwritten.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        vm.backups.take(20).forEach { backup ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(backup.label, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${formatTime(backup.time)} · ${backup.fileCount} file(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row {
                                        TextButton(
                                            enabled = !busy && vm.saveLocation != null,
                                            onClick = { vm.dialog = DialogState.ConfirmRestore(backup) },
                                        ) { Text("Restore") }
                                        TextButton(enabled = !busy, onClick = { vm.deleteBackup(backup) }) {
                                            Text("Delete")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    "Levels are downloaded through the MariOver API by TheGreatRambler.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Blocks the screen while a task runs.
        vm.busyMessage?.let { BusyOverlay(it) }
    }

    if (showSlotPicker) {
        SlotPickerDialog(
            vm = vm,
            onPick = {
                vm.slotInput = it.toString()
                showSlotPicker = false
            },
            onDismiss = { showSlotPicker = false },
        )
    }
    vm.dialog?.let { Dialogs(it, vm) }
}

@Composable
private fun StepCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LevelPreview(info: MariOverApi.LevelInfo, vm: MainViewModel) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            vm.thumbnail?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Level thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
            }
            when (vm.previewSource) {
                PreviewSource.DEVICE -> "The server is down, so this is the copy saved on your device."
                PreviewSource.ARCHIVE -> "The server is down, so this comes from the 2022 level archive " +
                    "(no thumbnail or maker info)."
                PreviewSource.SERVER -> null
            }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(info.name, style = MaterialTheme.typography.titleMedium)
            Text("by ${info.maker}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${info.gameStyle} · ${info.theme} · ${info.difficulty} · clear rate ${info.clearRate}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("${info.plays} plays · ${info.likes} likes", style = MaterialTheme.typography.bodySmall)
            if (info.description.isNotBlank()) {
                Text(info.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SlotStatus(vm: MainViewModel) {
    val slot = vm.parsedSlot()
    if (slot == null) {
        Text(
            "Slots 0–119 are My Courses (you can edit them). Slots 120–179 are Downloaded Courses " +
                "(play only). Slot 0 is the first course in Coursebot.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val kind = if (slot < MY_COURSES_SLOTS) "My Courses — editable" else "Downloaded Courses — play only"
    Text("Slot $slot: $kind", fontWeight = FontWeight.Medium)
    vm.history[slot]?.let {
        Text(
            "Last injected here: ${it.name} (${MariOverApi.formatCode(it.code)}) on ${formatTime(it.time)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (vm.saveLocation != null && courseDataName(slot) !in vm.saveFileNames) {
        Text(
            "This slot is empty in your save, so the game probably won't show an injected level here. " +
                "Save any course into it in-game first, or install the pre-loaded save below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
internal fun SlotPickerDialog(vm: MainViewModel, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Pick a slot") },
        text = {
            Column {
                Text(
                    "Filled = has a course in your save · ★ = you injected a level here",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(56.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items((0 until TOTAL_SLOTS).toList()) { slot ->
                        val filled = courseDataName(slot) in vm.saveFileNames
                        val label = slot.toString() + if (slot in vm.history) "★" else ""
                        val border = if (slot < MY_COURSES_SLOTS) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                        if (filled) {
                            Button(onClick = { onPick(slot) }, contentPadding = SmallPadding) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { onPick(slot) },
                                border = BorderStroke(1.dp, border),
                                contentPadding = SmallPadding,
                            ) { Text(label) }
                        }
                    }
                }
            }
        },
    )
}

private val SmallPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 4.dp)

@Composable
private fun ServerStatusRow(vm: MainViewModel) {
    val (color, text) = when (vm.serverStatus) {
        null -> MaterialTheme.colorScheme.outline to "Checking level server…"
        MariOverApi.ServerStatus.ONLINE -> Color(0xFF2E7D32) to "Level server is online"
        MariOverApi.ServerStatus.BUSY -> Color(0xFFF9A825) to "Level server is busy, try again in a minute"
        MariOverApi.ServerStatus.DOWN -> MaterialTheme.colorScheme.error to
            "Level server is down. Only levels saved on this device can be used"
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (vm.serverStatus != null) {
                    Text(
                        "Checked ${formatClock(vm.serverCheckedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(enabled = vm.serverStatus != null, onClick = vm::checkServer) { Text("Check") }
        }
    }
}

@Composable
private fun SavedLevelsCard(vm: MainViewModel, busy: Boolean) {
    StepCard("Saved levels") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Save downloaded levels on this device",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = vm.saveLevels, onCheckedChange = vm::updateSaveLevels)
        }
        Text(
            "When on, every level you preview or inject is kept on your device, so you can use it again " +
                "even when the level server is down. Saved levels are always used when available.",
            style = MaterialTheme.typography.bodySmall,
        )
        vm.savedLevels.forEach { level ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(enabled = !busy) { vm.useSavedLevel(level.code) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(level.info?.name ?: "Unknown level", fontWeight = FontWeight.Medium)
                        Text(
                            MariOverApi.formatCode(level.code) +
                                (level.info?.let { " · by ${it.maker}" } ?: "") +
                                (if (level.hasData) "" else " · info only"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(enabled = !busy, onClick = { vm.useSavedLevel(level.code) }) { Text("Use") }
                    TextButton(enabled = !busy, onClick = { vm.deleteSavedLevel(level.code) }) { Text("Delete") }
                }
            }
        }
        if (vm.savedLevels.size > 1) {
            TextButton(enabled = !busy, onClick = { vm.dialog = DialogState.ConfirmDeleteSavedLevels }) {
                Text("Delete all saved levels")
            }
        }
    }
}

/** Shows the 9-character code as ABC-DEF-GHI while the field itself only holds the characters. */
private object CodeDashes : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = buildString {
            raw.forEachIndexed { i, c ->
                append(c)
                if (i == 2 || i == 5) append('-')
            }
        }
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                offset + (if (offset >= 3) 1 else 0) + (if (offset >= 6) 1 else 0)

            override fun transformedToOriginal(offset: Int): Int =
                (offset - (if (offset >= 4) 1 else 0) - (if (offset >= 8) 1 else 0)).coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}

@Composable
private fun BusyOverlay(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            // Swallow touches so nothing behind the overlay can be tapped.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.padding(32.dp)) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Spacer(Modifier.width(16.dp))
                Text(message)
            }
        }
    }
}

@Composable
private fun Dialogs(state: DialogState, vm: MainViewModel) {
    val context = LocalContext.current
    val dismiss = { vm.dialog = null }
    when (state) {
        is DialogState.Error -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Something went wrong") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = dismiss) { Text("OK") } },
        )

        is DialogState.Success -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Done!") },
            text = { Text(state.message) },
            confirmButton = {
                val eden = vm.edenPackage
                if (eden != null) {
                    TextButton(onClick = {
                        dismiss()
                        context.packageManager.getLaunchIntentForPackage(eden)?.let(context::startActivity)
                    }) { Text("Open Eden") }
                } else {
                    TextButton(onClick = dismiss) { Text("OK") }
                }
            },
            dismissButton = if (vm.edenPackage != null) {
                { TextButton(onClick = dismiss) { Text("Close") } }
            } else {
                null
            },
        )

        is DialogState.ConfirmEmptySlot -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Slot ${state.slot} is empty") },
            text = {
                Text(
                    "Your save has no course in slot ${state.slot}, so Super Mario Maker 2 will probably " +
                        "ignore the injected level. Save any course into that slot in-game first, or " +
                        "install the pre-loaded save.\n\nInject anyway?"
                )
            },
            confirmButton = { TextButton(onClick = { vm.inject(confirmedEmptySlot = true) }) { Text("Inject anyway") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )

        DialogState.ConfirmPreloaded -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Replace your SMM2 save?") },
            text = {
                Text(
                    "This downloads about 135 MB and replaces your Super Mario Maker 2 save (courses, " +
                        "progress and settings) with the pre-loaded one. Your current save is backed up " +
                        "first and can be restored from the Backups section.\n\nMake sure Eden is closed."
                )
            },
            confirmButton = { TextButton(onClick = vm::installPreloadedSave) { Text("Replace save") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )

        is DialogState.ConfirmRestore -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Restore backup?") },
            text = {
                Text(
                    "Write the ${state.backup.fileCount} file(s) from \"${state.backup.label}\" back into " +
                        "your save? Make sure Eden is closed."
                )
            },
            confirmButton = { TextButton(onClick = { vm.restoreBackup(state.backup) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )

        DialogState.ConfirmDeleteSavedLevels -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Delete all saved levels?") },
            text = { Text("All ${vm.savedLevels.size} levels saved on this device will be deleted.") },
            confirmButton = { TextButton(onClick = vm::deleteAllSavedLevels) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )

        is DialogState.ChooseSave -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Which save?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "More than one Super Mario Maker 2 save was found (one per Eden user profile). " +
                            "Pick the one Eden uses, normally the first one.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.candidates.forEach { candidate ->
                        OutlinedCard(onClick = { vm.chooseSave(candidate) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                candidate.displayPath,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
    }
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private fun formatClock(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** Folder picker that opens directly on Eden's files when Eden is installed. */
private class OpenEdenFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: android.content.Context, input: Uri?): Intent =
        super.createIntent(context, input).apply {
            if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
            addFlags(
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
}
