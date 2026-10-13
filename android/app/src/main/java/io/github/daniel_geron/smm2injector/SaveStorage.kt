package io.github.daniel_geron.smm2injector

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException

/** Super Mario Maker 2's title ID; its save folder is named after it. */
const val SMM2_TITLE_ID = "01009B90006DC000"

/** Slots 0-119 are "My Courses" (editable), 120-179 are "Downloaded Courses" (play only). */
const val MY_COURSES_SLOTS = 120
const val TOTAL_SLOTS = 180

fun courseDataName(slot: Int) = "course_data_%03d.bcd".format(slot)

/** Eden builds whose DocumentsProvider exposes the emulator's files (authority = "<package>.user"). */
val EDEN_PACKAGES = listOf(
    "dev.eden.eden_emulator",
    "dev.eden.eden_emulator.nightly",
    "dev.legacy.eden_emulator",
    "com.miHoYo.Yuanshen", // Eden "Optimized" build
)

fun edenProviderAuthority(pkg: String) = "$pkg.user"

/** The first installed Eden build, if any. */
fun findInstalledEden(context: Context): String? =
    EDEN_PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            context.packageManager.resolveContentProvider(edenProviderAuthority(pkg), 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

/**
 * Eden's root, used to open the folder picker directly on it. This has to be a root URI rather
 * than a document URI: Eden's provider doesn't implement findDocumentPath, which the picker
 * needs to open on a document.
 */
fun edenRootUri(pkg: String): Uri =
    DocumentsContract.buildRootUri(edenProviderAuthority(pkg), "root")

/** Package of the Eden build that owns a tree URI, or null if it's some other folder. */
fun edenPackageForTree(treeUri: Uri): String? =
    EDEN_PACKAGES.firstOrNull { edenProviderAuthority(it) == treeUri.authority }

data class DocEntry(val id: String, val name: String, val isDir: Boolean, val size: Long)

/** A Super Mario Maker 2 save folder found inside the picked tree. */
data class SaveLocation(val docId: String, val displayPath: String, val profile: String)

/**
 * Thin wrapper over the Storage Access Framework for a folder tree the user granted us
 * (normally Eden's own files, exposed through Eden's DocumentsProvider).
 */
class SafTree(private val resolver: ContentResolver, val treeUri: Uri) {
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    fun list(parentId: String): List<DocEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
        )
        val result = mutableListOf<DocEntry>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                result += DocEntry(
                    id = c.getString(0),
                    name = c.getString(1) ?: "",
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                )
            }
        } ?: throw IOException("Couldn't read the selected folder. Try selecting it again.")
        return result
    }

    /**
     * Searches the tree for SMM2 save folders. Eden uses either
     *   nand/user/save/0000000000000000/<user id>/01009B90006DC000/   (classic layout) or
     *   nand/user/save/account/<user uuid>/01009B90006DC000/0/        (newer layout),
     * and the user may have picked Eden's root, the nand folder, or a custom save folder.
     */
    fun findSmm2Saves(): List<SaveLocation> {
        data class Node(val id: String, val path: String, val depth: Int)

        val found = mutableListOf<SaveLocation>()
        val queue = ArrayDeque(listOf(Node(rootDocId, "", 0)))
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_DIRS_TO_SEARCH) {
            val node = queue.removeFirst()
            visited++
            val children = try {
                list(node.id)
            } catch (e: Exception) {
                continue
            }
            for (child in children) {
                if (!child.isDir) continue
                val childPath = if (node.path.isEmpty()) child.name else "${node.path}/${child.name}"
                if (child.name.equals(SMM2_TITLE_ID, ignoreCase = true)) {
                    val profile = node.path.substringAfterLast('/')
                    val inner = runCatching { list(child.id) }.getOrDefault(emptyList())
                    val newLayoutDir = inner.firstOrNull { it.isDir && it.name == "0" }
                    found += if (newLayoutDir != null) {
                        SaveLocation(newLayoutDir.id, "$childPath/0", profile)
                    } else {
                        SaveLocation(child.id, childPath, profile)
                    }
                } else if (node.depth < MAX_DEPTH && child.name.lowercase() !in SKIP_DIRS) {
                    queue.addLast(Node(child.id, childPath, node.depth + 1))
                }
            }
        }
        return found
    }

    fun readFile(docId: String): ByteArray =
        resolver.openInputStream(docUri(docId))?.use { it.readBytes() }
            ?: throw FileNotFoundException("Couldn't open file for reading")

    /** Overwrites [name] in [dirId] (creating it if needed) with [data]. */
    fun writeFile(dirId: String, existing: List<DocEntry>, name: String, data: ByteArray) {
        val fileId = existing.firstOrNull { !it.isDir && it.name == name }?.id
            ?: DocumentsContract.createDocument(resolver, docUri(dirId), "application/octet-stream", name)
                ?.let { DocumentsContract.getDocumentId(it) }
            ?: throw IOException("Couldn't create $name")
        val out = resolver.openOutputStream(docUri(fileId), "wt")
            ?: throw IOException("Couldn't open $name for writing")
        out.use { it.write(data) }
    }

    companion object {
        private const val MAX_DEPTH = 7
        private const val MAX_DIRS_TO_SEARCH = 4000

        /** Big folders that never contain save data; skipping them keeps the search fast. */
        private val SKIP_DIRS = setOf(
            "registered", "placehld", "contents", "shader", "load", "dump", "log", "cache",
            "screenshots", "sdmc", "keys", "amiibo", "gpu_drivers", "game_list", "crash_dumps",
        )
    }
}
