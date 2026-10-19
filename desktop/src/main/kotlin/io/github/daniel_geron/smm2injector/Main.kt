package io.github.daniel_geron.smm2injector

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.daniel_geron.smm2injector.archive.ArchiveJobs
import io.github.daniel_geron.smm2injector.archive.LevelArchive
import io.github.daniel_geron.smm2injector.archive.LevelNames
import java.awt.Desktop
import java.awt.Toolkit
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main() {
    // Native-looking folder pickers.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val store = AppStore()
    val archive = LevelArchive.get(store)
    val vm = MainViewModel(store, archive)
    val archiveVm = ArchiveViewModel(archive)
    application {
        // A phone-like portrait window by default, like the Android app; it can be resized freely.
        val screen = Toolkit.getDefaultToolkit().screenSize
        val state = rememberWindowState(
            size = DpSize(620.dp, minOf(980, screen.height - 80).dp),
            position = WindowPosition(Alignment.Center),
        )
        Window(
            onCloseRequest = {
                ArchiveJobs.stop()
                archive.close()
                exitApplication()
            },
            state = state,
            title = "SMM2 Level Downloader",
            icon = rememberVectorPainter(AppIconVector),
        ) {
            window.minimumSize = java.awt.Dimension(480, 600)
            SmmTheme { MainScreen(vm, archiveVm) }
        }
    }
}

private val Tabs = listOf("Download", "Archive", "Saved", "Backups")

@Composable
fun MainScreen(vm: MainViewModel, archiveVm: ArchiveViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        SmmFrame(
            title = "SMM2 Level Downloader",
            tabs = Tabs,
            selectedTab = tab,
            onTab = { tab = it },
            onBack = if (tab != 0) ({ tab = 0 }) else null,
            actions = { ServerPill(vm) },
        ) {
            // On a wide window the content stays at a readable width, centred.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 760.dp)) {
                    when (tab) {
                        0 -> DownloadTab(vm)
                        1 -> ArchiveTab(archiveVm, vm)
                        2 -> SavedTab(vm, onUse = { tab = 0 })
                        else -> BackupsTab(vm, onSelectSave = { tab = 0 })
                    }
                }
            }
        }
        // Blocks the screen while a task runs.
        vm.busyMessage?.let { BusyOverlay(it) }
    }
    vm.dialog?.let { Dialogs(it, vm) }
    if (vm.showWelcome) WelcomeDialog(onDismiss = vm::dismissWelcome)
}

/** Shown on the first launch: how to get started, and the pre-loaded save recommendation. */
@Composable
private fun WelcomeDialog(onDismiss: () -> Unit) {
    SmmDialog(
        onDismissRequest = {},
        title = "Welcome!",
        buttons = { SmmButton("Got it", highlighted = true, modifier = Modifier.weight(1f), onClick = onDismiss) },
    ) {
        Text(
            "This app puts Super Mario Maker 2 levels into your Coursebot in Eden or Ryujinx, by course " +
                "ID or from the level archive.",
            style = MaterialTheme.typography.bodyLarge,
        )
        SmmCard(Modifier.fillMaxWidth(), color = Smm.Cream) {
            Text("Recommended: use the pre-loaded save", style = MaterialTheme.typography.titleMedium, color = Smm.Orange)
            Text(
                "The game ignores levels injected into slots that are empty in your save. The pre-loaded " +
                    "save has a course in all 180 slots, so you can replace any of them. After choosing your " +
                    "emulator, click Install pre-loaded save (in the Download or Backups tab). Your current " +
                    "save is backed up first.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text("Getting started", style = MaterialTheme.typography.titleMedium)
        Text(
            "1. Start Super Mario Maker 2 in your emulator once, then close the emulator.\n" +
                "2. Choose Eden or Ryujinx in the Download tab. Its save is found automatically.\n" +
                "3. Always close the emulator before injecting a level.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SmallNote(
            "Downloading by course ID depends on the MariOver level server. When it's down, you can still " +
                "use levels saved on this PC and levels from the archive (uploaded up to February 2022)."
        )
    }
}

@Composable
private fun TabColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
private fun ServerPill(vm: MainViewModel) {
    val (color, text) = when (vm.serverStatus) {
        null -> Color(0xFF9EA3B5) to "Checking…"
        MariOverApi.ServerStatus.ONLINE -> Smm.Green to "Server online"
        MariOverApi.ServerStatus.BUSY -> Smm.Amber to "Server busy"
        MariOverApi.ServerStatus.DOWN -> Smm.Red to "Server down"
    }
    StatusPill(color, text, onClick = { if (vm.serverStatus != null) vm.checkServer() })
}

// ------------------------------------------------------------------------------------- Download tab

@Composable
private fun DownloadTab(vm: MainViewModel) {
    val focusManager = LocalFocusManager.current
    var showSlotPicker by remember { mutableStateOf(false) }
    val busy = vm.busyMessage != null

    TabColumn {
        ServerBanner(vm)

        EmulatorSection(vm, busy)

        SmmSection("Course ID") {
            SmmTextField(
                value = vm.codeInput,
                // Only letters/numbers are kept; the dashes are drawn by CodeDashes.
                onValueChange = { vm.codeInput = MariOverApi.normalizeCode(it).take(9) },
                placeholder = "ABC-DEF-GHI",
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    textAlign = TextAlign.Center,
                    letterSpacing = 3.sp,
                ),
                visualTransformation = CodeDashes,
                onEnter = {
                    focusManager.clearFocus()
                    vm.lookUpLevel()
                },
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
            SmmButton(
                "Preview level",
                style = SmmButtonStyle.White,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    focusManager.clearFocus()
                    vm.lookUpLevel()
                },
            )
        }
        vm.levelInfo?.let { info -> LevelPreview(info, vm) }

        SmmSection("Slot to overwrite") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmmTextField(
                    value = vm.slotInput,
                    onValueChange = { vm.slotInput = it.filter(Char::isDigit).take(3) },
                    placeholder = "0–179",
                    textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    onEnter = { focusManager.clearFocus() },
                    modifier = Modifier.width(120.dp),
                )
                SmmButton("Pick…", style = SmmButtonStyle.White, onClick = { showSlotPicker = true })
            }
            SlotStatus(vm)
        }

        SmmButton(
            "Download & inject",
            enabled = !busy,
            highlighted = true,
            large = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            onClick = {
                focusManager.clearFocus()
                vm.inject()
            },
        )
        SmallNote(
            "⚠ Close ${vm.emulatorName} before injecting, otherwise the game can overwrite the new level " +
                "when it saves. You'll be warned if it's still running."
        )
        SmallNote("Levels are downloaded through the MariOver API by TheGreatRambler.")
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
}

/** Choosing Eden or Ryujinx, and the Super Mario Maker 2 save found for it. */
@Composable
private fun EmulatorSection(vm: MainViewModel, busy: Boolean) {
    SmmSection("Emulator") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Emulator.entries.forEach { e ->
                SmmChip(e.label, selected = vm.emulator == e, onClick = { if (!busy) vm.selectEmulator(e) })
            }
        }
        val emulator = vm.emulator ?: run {
            Text("Choose the emulator you play Super Mario Maker 2 on.")
            return@SmmSection
        }
        val location = vm.saveLocation
        if (location == null) {
            Text("No Super Mario Maker 2 save selected yet.")
            SmallNote(
                vm.emulatorRoot?.let { "Searched ${it.path}" }
                    ?: "${emulator.label}'s folder wasn't found in its usual place. If it's portable, choose its folder."
            )
        } else {
            Text("Super Mario Maker 2 save found ✓", color = Smm.Green, fontWeight = FontWeight.ExtraBold)
            SmallNote(location.displayPath)
            val filled = (0 until TOTAL_SLOTS).count { courseDataName(it) in vm.saveFileNames }
            SmallNote("$filled of $TOTAL_SLOTS course slots have a course file.")
            if (filled < TOTAL_SLOTS) {
                Text(
                    "Recommended: install the pre-loaded save, which has a course in every slot. " +
                        "The game ignores levels injected into empty slots.",
                    color = Smm.Orange,
                )
                SmmButton(
                    "Install pre-loaded save (~135 MB)",
                    small = true,
                    enabled = !busy,
                    onClick = { vm.dialog = DialogState.ConfirmPreloaded },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmmButton(
                "Choose folder…",
                small = true,
                style = SmmButtonStyle.White,
                enabled = !busy,
                onClick = { pickFolder(emulator, vm.emulatorRoot)?.let(vm::onFolderPicked) },
            )
            SmmButton("Rescan", small = true, style = SmmButtonStyle.White, enabled = !busy, onClick = { vm.scanForSaves() })
            if (location != null) {
                SmmButton("Open folder", small = true, style = SmmButtonStyle.White, onClick = { openFolder(location.dir) })
            }
        }
        if (vm.emulatorRoot != null && vm.emulatorRoot !in emulator.defaultDataDirs) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallNote("Using a folder you chose.")
                SmmButton("Use default", small = true, style = SmmButtonStyle.White, enabled = !busy, onClick = vm::useDefaultFolder)
            }
        }
    }
}

/** Asks for the emulator's data folder (or its SMM2 save folder). */
private fun pickFolder(emulator: Emulator, current: File?): File? {
    val chooser = JFileChooser(current ?: emulator.defaultDataDirs.firstOrNull()?.parentFile).apply {
        dialogTitle = "Select ${emulator.label}'s data folder (or its Super Mario Maker 2 save folder)"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

internal fun openFolder(dir: File) {
    runCatching { Desktop.getDesktop().open(dir) }
}

/** Explains what still works when the level server isn't answering. */
@Composable
private fun ServerBanner(vm: MainViewModel) {
    val text = when (vm.serverStatus) {
        MariOverApi.ServerStatus.BUSY -> "The level server is busy. Try again in a minute."
        MariOverApi.ServerStatus.DOWN -> "The level server is down. Levels saved on this PC and levels in " +
            "the archive (uploaded before February 2022) can still be used."
        else -> return
    }
    SmmCard(Modifier.fillMaxWidth(), color = Color(0xFFFFE4D6)) {
        Text(text)
        SmallNote("Checked ${formatClock(vm.serverCheckedAt)} · click the status at the top to check again")
    }
}

/** An opened course, laid out like the course details in the game. */
@Composable
private fun LevelPreview(info: MariOverApi.LevelInfo, vm: MainViewModel) {
    SmmCard(Modifier.fillMaxWidth(), padding = 12.dp, spacing = 10.dp) {
        when (vm.previewSource) {
            PreviewSource.DEVICE -> "The server is down, so this is the copy saved on this PC."
            PreviewSource.ARCHIVE -> "The server is down, so this comes from the 2022 level archive " +
                "(no thumbnail or maker info)."
            PreviewSource.SERVER -> null
        }?.let { SmallNote(it, color = Smm.Orange) }
        CourseTitle(styleBadge = { StyleBadge(info.gameStyle) }, name = info.name)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val thumbModifier = Modifier
                .width(150.dp)
                .aspectRatio(16f / 9f)
            val thumbnail = vm.thumbnail
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "Level thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = thumbModifier.clip(RoundedCornerShape(4.dp)),
                )
            } else {
                ThemeThumbnail(LevelNames.themes.indexOfFirst { it.equals(info.theme, ignoreCase = true) }, null, thumbModifier)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeartStat(info.likes)
                PlaysStat(info.plays)
                Text("by ${info.maker}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (info.description.isNotBlank()) DescriptionBox(info.description)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoCell("Clear Rate", info.clearRate)
            InfoCell("Difficulty", info.difficulty)
            InfoCell("Course ID", MariOverApi.formatCode(info.code), weight = 1.5f)
        }
    }
}

/** Style badge and course name, as at the top of a course in the game's lists. */
@Composable
internal fun CourseTitle(styleBadge: @Composable () -> Unit, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        styleBadge()
        Text(
            name.ifBlank { "(no name)" },
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SlotStatus(vm: MainViewModel) {
    val slot = vm.parsedSlot()
    if (slot == null) {
        SmallNote(
            "Slots 0–119 are My Courses (you can edit them). Slots 120–179 are Downloaded Courses " +
                "(play only). Slot 0 is the first course in Coursebot."
        )
        return
    }
    val kind = if (slot < MY_COURSES_SLOTS) "My Courses — editable" else "Downloaded Courses — play only"
    Text("Slot $slot: $kind", fontWeight = FontWeight.ExtraBold)
    vm.history[slot]?.let {
        SmallNote("Last injected here: ${it.name} (${MariOverApi.formatCode(it.code)}) on ${formatTime(it.time)}")
    }
    if (vm.saveLocation != null && courseDataName(slot) !in vm.saveFileNames) {
        SmallNote(
            "This slot is empty in your save, so the game probably won't show an injected level here. " +
                "Save any course into it in-game first, or install the pre-loaded save.",
            color = Smm.Red,
        )
    }
}

@Composable
internal fun SlotPickerDialog(vm: MainViewModel, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    SmmDialog(
        onDismissRequest = onDismiss,
        title = "Pick a slot",
        onClose = onDismiss,
        scrollable = false,
    ) {
        SmallNote("White = has a course in your save · ★ = you injected a level here")
        LazyVerticalGrid(
            columns = GridCells.Adaptive(54.dp),
            modifier = Modifier.heightIn(max = 460.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { GridHeader("My Courses (editable)") }
            items((0 until MY_COURSES_SLOTS).toList()) { SlotTile(it, vm, onPick) }
            item(span = { GridItemSpan(maxLineSpan) }) { GridHeader("Downloaded Courses (play only)") }
            items((MY_COURSES_SLOTS until TOTAL_SLOTS).toList()) { SlotTile(it, vm, onPick) }
        }
    }
}

@Composable
private fun GridHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun SlotTile(slot: Int, vm: MainViewModel, onPick: (Int) -> Unit) {
    val filled = courseDataName(slot) in vm.saveFileNames
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .height(44.dp)
            .cursorCorners(pressed)
            .clip(shape)
            .background(if (filled) Color.White else Smm.Cream.copy(alpha = 0.6f))
            .border(1.5.dp, if (filled) Smm.Beige else Smm.Beige.copy(alpha = 0.6f), shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interaction, indication = null, role = Role.Button) { onPick(slot) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            slot.toString(),
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
            color = if (filled) Smm.Maroon else Smm.Maroon.copy(alpha = 0.4f),
        )
        if (slot in vm.history) {
            Icon(
                Icons.Filled.Star,
                "Injected",
                tint = Smm.Orange,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(13.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------- Saved tab

@Composable
private fun SavedTab(vm: MainViewModel, onUse: () -> Unit) {
    val busy = vm.busyMessage != null
    TabColumn {
        SmmSection("Saved levels") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Save downloaded levels on this PC", modifier = Modifier.weight(1f))
                Switch(checked = vm.saveLevels, onCheckedChange = vm::updateSaveLevels, colors = smmSwitchColors())
            }
            SmallNote(
                "When on, every level you preview or inject is kept on this PC, so you can use it again " +
                    "even when the level server is down. Saved levels are always used when available."
            )
        }
        if (vm.savedLevels.isEmpty()) {
            SmallNote("No levels saved yet.")
        }
        vm.savedLevels.forEach { level ->
            val use = {
                vm.useSavedLevel(level.code)
                onUse()
            }
            SmmCard(Modifier.fillMaxWidth(), onClick = if (busy) null else use, padding = 12.dp, spacing = 6.dp) {
                CourseTitle(
                    styleBadge = { level.info?.let { StyleBadge(it.gameStyle) } },
                    name = level.info?.name ?: "Unknown level",
                )
                SmallNote(
                    MariOverApi.formatCode(level.code) +
                        (level.info?.let { " · by ${it.maker}" } ?: "") +
                        (if (level.hasData) "" else " · info only")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmmButton("Use", small = true, enabled = !busy, onClick = use)
                    SmmButton(
                        "Delete",
                        small = true,
                        style = SmmButtonStyle.White,
                        enabled = !busy,
                        onClick = { vm.deleteSavedLevel(level.code) },
                    )
                }
            }
        }
        if (vm.savedLevels.size > 1) {
            SmmButton(
                "Delete all saved levels",
                style = SmmButtonStyle.White,
                enabled = !busy,
                onClick = { vm.dialog = DialogState.ConfirmDeleteSavedLevels },
            )
        }
    }
}

// -------------------------------------------------------------------------------------- Backups tab

@Composable
private fun BackupsTab(vm: MainViewModel, onSelectSave: () -> Unit) {
    val busy = vm.busyMessage != null
    TabColumn {
        SmmSection("Pre-loaded save (recommended)") {
            Text(
                "Super Mario Maker 2 ignores injected levels in slots that are empty in your save, so " +
                    "installing the pre-loaded save is recommended. It has every My Courses and Downloaded " +
                    "Courses slot filled, so any slot can be replaced. It replaces your SMM2 save; your " +
                    "current one is backed up first.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SmmButton(
                "Install pre-loaded save (~135 MB)",
                enabled = !busy && vm.saveLocation != null,
                onClick = { vm.dialog = DialogState.ConfirmPreloaded },
            )
            if (vm.saveLocation == null) {
                SmallNote("Choose your emulator in the Download tab first.")
                SmmButton("Go to the Download tab", small = true, style = SmmButtonStyle.White, onClick = onSelectSave)
            }
        }
        SmmSection("Backups") {
            SmallNote("Files are backed up automatically before they're overwritten.")
            SmmButton("Open backups folder", small = true, style = SmmButtonStyle.White, onClick = {
                openFolder(File(appDir, "backups").apply { mkdirs() })
            })
            if (vm.backups.isEmpty()) Text("No backups yet.")
        }
        vm.backups.take(20).forEach { backup ->
            SmmCard(Modifier.fillMaxWidth(), padding = 12.dp, spacing = 6.dp) {
                Text(backup.label, style = MaterialTheme.typography.titleMedium)
                SmallNote("${formatTime(backup.time)} · ${backup.fileCount} file(s)")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmmButton(
                        "Restore",
                        small = true,
                        enabled = !busy && vm.saveLocation != null,
                        onClick = { vm.dialog = DialogState.ConfirmRestore(backup) },
                    )
                    SmmButton(
                        "Delete",
                        small = true,
                        style = SmmButtonStyle.White,
                        enabled = !busy,
                        onClick = { vm.deleteBackup(backup) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------- shared

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
        SmmCard(Modifier.padding(32.dp), color = Smm.CreamDark, padding = 24.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(32.dp), color = Smm.Orange, trackColor = Smm.Beige)
                Spacer(Modifier.width(16.dp))
                Text(message, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** A dialog with Cancel and one action, the most common shape. */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
    onDismissButton: () -> Unit = onDismiss,
) {
    SmmDialog(
        onDismissRequest = onDismiss,
        title = title,
        buttons = {
            SmmButton(dismiss, style = SmmButtonStyle.White, modifier = Modifier.weight(1f), onClick = onDismissButton)
            SmmButton(confirm, highlighted = true, modifier = Modifier.weight(1f), onClick = onConfirm)
        },
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Dialogs(state: DialogState, vm: MainViewModel) {
    val dismiss = { vm.dialog = null }
    when (state) {
        is DialogState.Error -> SmmDialog(
            onDismissRequest = dismiss,
            title = "Something went wrong",
            buttons = { SmmButton("OK", highlighted = true, modifier = Modifier.weight(1f), onClick = dismiss) },
        ) { Text(state.message, style = MaterialTheme.typography.bodyLarge) }

        is DialogState.Success -> SmmDialog(
            onDismissRequest = dismiss,
            title = "Done!",
            buttons = { SmmButton("OK", highlighted = true, modifier = Modifier.weight(1f), onClick = dismiss) },
        ) { Text(state.message, style = MaterialTheme.typography.bodyLarge) }

        is DialogState.ConfirmEmptySlot -> ConfirmDialog(
            title = "Slot ${state.slot} is empty",
            text = "Your save has no course in slot ${state.slot}, so Super Mario Maker 2 will probably " +
                "ignore the injected level. Save any course into that slot in-game first, or " +
                "install the pre-loaded save.\n\nInject anyway?",
            confirm = "Inject anyway",
            onConfirm = { vm.inject(confirmedEmptySlot = true) },
            onDismiss = dismiss,
        )

        DialogState.ConfirmPreloaded -> ConfirmDialog(
            title = "Replace your SMM2 save?",
            text = "This downloads about 135 MB and replaces your Super Mario Maker 2 save (courses, " +
                "progress and settings) with the pre-loaded one. Your current save is backed up " +
                "first and can be restored from the Backups tab.\n\nMake sure ${vm.emulatorName} is closed.",
            confirm = "Replace save",
            onConfirm = vm::installPreloadedSave,
            onDismiss = dismiss,
        )

        is DialogState.ConfirmRestore -> ConfirmDialog(
            title = "Restore backup?",
            text = "Write the ${state.backup.fileCount} file(s) from \"${state.backup.label}\" back into " +
                "your ${vm.emulatorName} save?\n\n${vm.saveLocation?.displayPath.orEmpty()}\n\n" +
                "Make sure ${vm.emulatorName} is closed.",
            confirm = "Restore",
            onConfirm = { vm.restoreBackup(state.backup) },
            onDismiss = dismiss,
        )

        DialogState.ConfirmDeleteSavedLevels -> ConfirmDialog(
            title = "Delete all saved levels?",
            text = "All ${vm.savedLevels.size} levels saved on this PC will be deleted.",
            confirm = "Delete all",
            onConfirm = vm::deleteAllSavedLevels,
            onDismiss = dismiss,
        )

        is DialogState.ChooseSave -> SmmDialog(
            onDismissRequest = dismiss,
            title = "Which save?",
            buttons = { SmmButton("Cancel", style = SmmButtonStyle.White, modifier = Modifier.weight(1f), onClick = dismiss) },
        ) {
            Text(
                "More than one Super Mario Maker 2 save was found (one per ${vm.emulatorName} user profile). " +
                    "Pick the one for the profile you play on, normally the first one.",
                style = MaterialTheme.typography.bodyMedium,
            )
            state.candidates.forEach { candidate ->
                SmmCard(Modifier.fillMaxWidth(), onClick = { vm.chooseSave(candidate) }, padding = 12.dp) {
                    Text(candidate.displayPath, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        is DialogState.EmulatorRunning -> ConfirmDialog(
            title = "${state.emulator.label} is running",
            text = "Close ${state.emulator.label} first. If it's open while the save changes, the game can " +
                "overwrite the change with its old data when it saves or closes.",
            confirm = "Continue anyway",
            onConfirm = { vm.dialog = null; state.action() },
            onDismiss = dismiss,
        )
    }
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

private fun formatClock(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
