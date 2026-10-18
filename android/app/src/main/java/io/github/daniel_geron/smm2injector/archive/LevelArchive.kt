package io.github.daniel_geron.smm2injector.archive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Inflater
import org.json.JSONArray

/** One level as stored in the on-device index. */
data class ArchiveLevel(
    val dataId: Long,
    val name: String,
    val style: Int,
    val theme: Int,
    val difficulty: Int,
    val tag1: Int,
    val tag2: Int,
    val clearRate: Double,
    val likes: Int,
    val plays: Int,
    val clears: Int,
    val attempts: Int,
    val boos: Int,
    val uploaded: Long,
    val worldRecordMs: Int,
) {
    val code: String get() = CourseCode.fromDataId(dataId)
}

data class ArchiveQuery(
    val search: String = "",
    val style: Int? = null,
    val theme: Int? = null,
    val difficulty: Int? = null,
    val tag: Int? = null,
    val minClearRate: Float = 0f,
    val maxClearRate: Float = 100f,
    val sort: Sort = Sort.MOST_LIKED,
) {
    enum class Sort(val label: String, val orderBy: String) {
        MOST_LIKED("Most liked", "likes DESC"),
        MOST_PLAYED("Most played", "plays DESC"),
        NEWEST("Newest", "data_id DESC"),
        OLDEST("Oldest", "data_id ASC"),
        HARDEST("Hardest", "clear_rate ASC"),
        EASIEST("Easiest", "clear_rate DESC"),
    }
}

data class ShardInfo(
    val index: Int,
    val path: String,
    val size: Long,
    val indexed: Boolean,
    val downloaded: Boolean,
)

/**
 * TheGreatRambler's archive of 26.6 million SMM2 levels (Hugging Face dataset
 * TheGreatRambler/mm2_level, CC BY-NC-SA 4.0, collected February 2022).
 *
 * The dataset is 196 Parquet files (~85 GB). The app keeps a searchable index of the small
 * columns in SQLite and reads a level's data either from a downloaded file or, if the file
 * isn't downloaded, straight from Hugging Face with HTTP range requests.
 */
class LevelArchive private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("archive", Context.MODE_PRIVATE)

    /** Storage volumes the archive can live on (internal storage first, then SD cards). */
    val volumes: List<File>
        get() = context.getExternalFilesDirs(null).filterNotNull()

    var root: File
        get() = prefs.getString("root", null)?.let(::File) ?: volumes.first()
        set(value) {
            close()
            prefs.edit { putString("root", value.absolutePath) }
        }

    private val archiveDir get() = File(root, "archive").apply { mkdirs() }
    private val shardDir get() = File(archiveDir, "files").apply { mkdirs() }
    private val dbFile get() = File(archiveDir, "index.db")

    private var db: SQLiteDatabase? = null
    private val openFiles = HashMap<Int, ParquetFile>()

    @Synchronized
    fun database(): SQLiteDatabase = db ?: SQLiteDatabase.openOrCreateDatabase(dbFile, null).also {
        it.execSQL(
            """CREATE TABLE IF NOT EXISTS levels(
                data_id INTEGER PRIMARY KEY, shard INT, rg INT, row INT, name TEXT,
                style INT, theme INT, difficulty INT, tag1 INT, tag2 INT, clear_rate REAL,
                likes INT, plays INT, clears INT, attempts INT, boos INT, uploaded INT, world_record INT)"""
        )
        it.execSQL(
            """CREATE TABLE IF NOT EXISTS shards(
                idx INTEGER PRIMARY KEY, path TEXT, size INT,
                indexed_groups INT DEFAULT 0, total_groups INT DEFAULT -1, downloaded INT DEFAULT 0,
                min_id INT, max_id INT)"""
        )
        // Row group data id ranges, used to find a level without the full index.
        it.execSQL("CREATE TABLE IF NOT EXISTS row_groups(shard INT, rg INT, min_id INT, max_id INT, PRIMARY KEY(shard, rg))")
        db = it
    }

    @Synchronized
    fun close() {
        db?.close()
        db = null
        openFiles.values.forEach { runCatching { it.close() } }
        openFiles.clear()
    }

    fun freeBytes(dir: File = root): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0)

    // ---------------------------------------------------------------- file list

    /** Fetches the list of the dataset's files from Hugging Face (once). */
    fun shards(): List<ShardInfo> {
        val db = database()
        if (db.count("SELECT COUNT(*) FROM shards") == 0L) {
            val json = JSONArray(httpGetText("$API/tree/main/data"))
            val files = (0 until json.length()).map { json.getJSONObject(it) }
                .filter { it.getString("path").endsWith(".parquet") }
                .sortedBy { it.getString("path") }
            db.inTransaction {
                files.forEachIndexed { i, f ->
                    db.insert("shards", null, ContentValues().apply {
                        put("idx", i)
                        put("path", f.getString("path"))
                        put("size", f.getLong("size"))
                    })
                }
            }
        }
        return db.rawQuery("SELECT idx, path, size, indexed_groups, total_groups, downloaded FROM shards ORDER BY idx", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(ShardInfo(c.getInt(0), c.getString(1), c.getLong(2), c.getInt(4) >= 0 && c.getInt(3) >= c.getInt(4), c.getInt(5) == 1))
                }
            }
        }
    }

    fun totalSize(): Long = database().count("SELECT COALESCE(SUM(size), 0) FROM shards")

    fun indexedLevelCount(): Long = database().count("SELECT COUNT(*) FROM levels")

    // ---------------------------------------------------------------- reading files

    private fun localFile(shard: ShardInfo) = File(shardDir, shard.path.substringAfterLast('/'))

    @Synchronized
    private fun open(shard: ShardInfo): ParquetFile =
        openFiles.getOrPut(shard.index) {
            val local = localFile(shard)
            val source = if (shard.downloaded && local.exists()) FileSource(local) else HttpSource("$FILES/${shard.path}", shard.size)
            ParquetFile(source).also { pq ->
                // Remember each row group's data id range so levels can be found without the index.
                val db = database()
                db.inTransaction {
                    pq.rowGroups.forEachIndexed { rg, group ->
                        val c = group.columns["data_id"] ?: return@forEachIndexed
                        db.insertWithOnConflict("row_groups", null, ContentValues().apply {
                            put("shard", shard.index)
                            put("rg", rg)
                            put("min_id", c.minStat?.let { ParquetFile.le32(it, 0) })
                            put("max_id", c.maxStat?.let { ParquetFile.le32(it, 0) })
                        }, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                    db.execSQL("UPDATE shards SET total_groups = ? WHERE idx = ? AND total_groups < 0", arrayOf(pq.rowGroups.size, shard.index))
                }
            }
        }

    // ---------------------------------------------------------------- building the index

    /**
     * Adds one row group of one file to the index. Downloads only the small columns
     * (~1 MB per 10,000 levels) unless the file is already on the device.
     */
    fun fetchGroup(shard: ShardInfo, rg: Int): Map<String, List<Any?>> = open(shard).readColumns(rg, INDEX_COLUMNS)

    /** Writes a row group fetched with [fetchGroup] into the index; returns the number of levels. */
    fun insertGroup(shard: ShardInfo, rg: Int, cols: Map<String, List<Any?>>): Int {
        val ids = cols.getValue("data_id")
        val db = database()
        db.inTransaction {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO levels VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            )
            for (row in ids.indices) {
                val id = ids[row] as Int? ?: continue
                stmt.clearBindings()
                stmt.bindLong(1, id.toLong())
                stmt.bindLong(2, shard.index.toLong())
                stmt.bindLong(3, rg.toLong())
                stmt.bindLong(4, row.toLong())
                stmt.bindString(5, (cols.getValue("name")[row] as ByteArray?)?.toString(Charsets.UTF_8).orEmpty())
                listOf("gamestyle", "theme", "difficulty", "tag1", "tag2").forEachIndexed { i, c ->
                    stmt.bindLong(6 + i, ((cols.getValue(c)[row] as Int?) ?: 0).toLong())
                }
                stmt.bindDouble(11, (cols.getValue("clear_rate")[row] as Double?) ?: 0.0)
                listOf("likes", "plays", "clears", "attempts", "boos", "uploaded", "world_record").forEachIndexed { i, c ->
                    stmt.bindLong(12 + i, ((cols.getValue(c)[row] as Int?) ?: 0).toLong())
                }
                stmt.executeInsert()
            }
            db.execSQL("UPDATE shards SET indexed_groups = ? WHERE idx = ?", arrayOf(rg + 1, shard.index))
        }
        return ids.size
    }

    /** Row groups of [shard] still to be indexed. */
    fun pendingGroups(shard: ShardInfo): IntRange {
        val pq = open(shard)
        val done = database().count("SELECT indexed_groups FROM shards WHERE idx = ${shard.index}").toInt()
        return done until pq.rowGroups.size
    }

    /** Builds the sort indexes once all files are indexed (much faster than updating them row by row). */
    fun finishIndex() {
        val db = database()
        db.execSQL("CREATE INDEX IF NOT EXISTS levels_likes ON levels(likes)")
        db.execSQL("CREATE INDEX IF NOT EXISTS levels_plays ON levels(plays)")
        db.execSQL("CREATE INDEX IF NOT EXISTS levels_clear_rate ON levels(clear_rate)")
    }

    // ---------------------------------------------------------------- downloading files

    /** Downloads one whole file (resumable). [progress] gets the bytes written so far. */
    fun download(shard: ShardInfo, isCancelled: () -> Boolean, progress: (Long) -> Unit) {
        val target = localFile(shard)
        val part = File(target.path + ".part")
        var have = if (part.exists()) part.length() else 0L
        while (have < shard.size) {
            if (isCancelled()) return
            val conn = URL("$FILES/${shard.path}").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", HttpSource.USER_AGENT)
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code != 200 && code != 206) throw IOException("Download failed (HTTP $code)")
                if (code == 200) have = 0 // server ignored the range; start over
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(have)
                    out.seek(have)
                    val buf = ByteArray(256 * 1024)
                    conn.inputStream.use { input ->
                        while (true) {
                            if (isCancelled()) return
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            progress(have)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        if (part.length() != shard.size) throw IOException("Download of ${target.name} is incomplete")
        part.renameTo(target)
        synchronized(this) { openFiles.remove(shard.index)?.close() }
        database().execSQL("UPDATE shards SET downloaded = 1 WHERE idx = ?", arrayOf(shard.index))
    }

    fun deleteDownloads() {
        close()
        shardDir.deleteRecursively()
        database().execSQL("UPDATE shards SET downloaded = 0")
    }

    fun deleteEverything() {
        close()
        archiveDir.deleteRecursively()
    }

    fun downloadedBytes(): Long = shardDir.listFiles().orEmpty().sumOf { it.length() }

    fun indexBytes(): Long = dbFile.length() + File(dbFile.path + "-journal").length()

    // ---------------------------------------------------------------- browsing

    fun search(q: ArchiveQuery, offset: Int, limit: Int): List<ArchiveLevel> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (q.search.isNotBlank()) {
            where += "name LIKE ?"
            args += "%${q.search.trim()}%"
        }
        q.style?.let { where += "style = $it" }
        q.theme?.let { where += "theme = $it" }
        q.difficulty?.let { where += "difficulty = $it" }
        q.tag?.let { where += "(tag1 = $it OR tag2 = $it)" }
        if (q.minClearRate > 0f) where += "clear_rate >= ${q.minClearRate}"
        if (q.maxClearRate < 100f) where += "clear_rate <= ${q.maxClearRate}"
        val sql = "SELECT data_id, name, style, theme, difficulty, tag1, tag2, clear_rate, likes, plays, " +
            "clears, attempts, boos, uploaded, world_record FROM levels" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY ${q.sort.orderBy} LIMIT $limit OFFSET $offset"
        return database().rawQuery(sql, args.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    ArchiveLevel(
                        dataId = c.getLong(0), name = c.getString(1), style = c.getInt(2), theme = c.getInt(3),
                        difficulty = c.getInt(4), tag1 = c.getInt(5), tag2 = c.getInt(6), clearRate = c.getDouble(7),
                        likes = c.getInt(8), plays = c.getInt(9), clears = c.getInt(10), attempts = c.getInt(11),
                        boos = c.getInt(12), uploaded = c.getLong(13), worldRecordMs = c.getInt(14),
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------- getting a level

    /** Where a level lives in the dataset, from the index or, without it, from the files' metadata. */
    private fun locate(dataId: Long): Triple<ShardInfo, Int, Int>? {
        val shards = shards()
        database().rawQuery("SELECT shard, rg, row FROM levels WHERE data_id = $dataId", null).use { c ->
            if (c.moveToFirst()) return Triple(shards[c.getInt(0)], c.getInt(1), c.getInt(2))
        }
        // Not indexed: the files are sorted by data id, so find the right file and row group.
        val (shard, rg) = findGroup(dataId, shards) ?: return null
        val ids = open(shard).readColumn(rg, "data_id")
        val row = ids.indexOf(dataId.toInt()).takeIf { it >= 0 } ?: return null
        return Triple(shard, rg, row)
    }

    private fun findGroup(dataId: Long, shards: List<ShardInfo>): Pair<ShardInfo, Int>? {
        fun lookup(): Pair<ShardInfo, Int>? =
            database().rawQuery("SELECT shard, rg FROM row_groups WHERE min_id <= $dataId AND max_id >= $dataId", null).use { c ->
                if (c.moveToFirst()) shards[c.getInt(0)] to c.getInt(1) else null
            }
        lookup()?.let { return it }
        // Binary search over the files, opening only the ones needed (each costs one small request).
        var lo = 0
        var hi = shards.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val groups = open(shards[mid]).rowGroups
            val min = groups.first().columns["data_id"]?.minStat?.let { ParquetFile.le32(it, 0) } ?: return null
            val max = groups.last().columns["data_id"]?.maxStat?.let { ParquetFile.le32(it, 0) } ?: return null
            when {
                dataId < min -> hi = mid - 1
                dataId > max -> lo = mid + 1
                else -> return lookup()
            }
        }
        return null
    }

    /** True if the level was uploaded before the archive was made (it may still have been deleted). */
    fun mightContain(dataId: Long): Boolean = dataId in MIN_DATA_ID..MAX_DATA_ID

    /** The level as an encrypted course file ready to inject, or null if it isn't in the archive. */
    fun courseFile(dataId: Long): ByteArray? {
        val (shard, rg, row) = locate(dataId) ?: return null
        val compressed = open(shard).readValue(rg, "level_data", row) as ByteArray? ?: return null
        return CourseCrypto.encrypt(inflate(compressed))
    }

    fun description(dataId: Long): String? {
        val (shard, rg, row) = locate(dataId) ?: return null
        return (open(shard).readValue(rg, "description", row) as ByteArray?)?.toString(Charsets.UTF_8)
    }

    /** A level's details from the index, or straight from the archive files if it isn't indexed. */
    fun lookup(dataId: Long): ArchiveLevel? {
        level(dataId)?.let { return it }
        if (!mightContain(dataId)) return null
        val (shard, rg, row) = locate(dataId) ?: return null
        val cols = open(shard).readColumns(rg, INDEX_COLUMNS)
        fun int(c: String) = (cols.getValue(c)[row] as Int?) ?: 0
        return ArchiveLevel(
            dataId = dataId,
            name = (cols.getValue("name")[row] as ByteArray?)?.toString(Charsets.UTF_8).orEmpty(),
            style = int("gamestyle"), theme = int("theme"), difficulty = int("difficulty"),
            tag1 = int("tag1"), tag2 = int("tag2"),
            clearRate = (cols.getValue("clear_rate")[row] as Double?) ?: 0.0,
            likes = int("likes"), plays = int("plays"), clears = int("clears"), attempts = int("attempts"),
            boos = int("boos"), uploaded = int("uploaded").toLong(), worldRecordMs = int("world_record"),
        )
    }

    fun level(dataId: Long): ArchiveLevel? =
        database().rawQuery(
                "SELECT data_id, name, style, theme, difficulty, tag1, tag2, clear_rate, likes, plays, clears, attempts, boos, uploaded, world_record FROM levels WHERE data_id = $dataId",
                null,
            ).use { c ->
                if (!c.moveToFirst()) null else ArchiveLevel(
                    c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getInt(5), c.getInt(6),
                    c.getDouble(7), c.getInt(8), c.getInt(9), c.getInt(10), c.getInt(11), c.getInt(12), c.getLong(13), c.getInt(14),
                )
            }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArray(CourseCrypto.DECRYPTED_SIZE)
            var n = 0
            while (n < out.size && !inflater.finished()) {
                val r = inflater.inflate(out, n, out.size - n)
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
            if (n != out.size) throw IOException("Archive level data is damaged")
            return out
        } finally {
            inflater.end()
        }
    }

    private fun httpGetText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", HttpSource.USER_AGENT)
            if (conn.responseCode != 200) throw IOException("Couldn't reach Hugging Face (HTTP ${conn.responseCode})")
            return conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun SQLiteDatabase.count(sql: String): Long = rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    private inline fun SQLiteDatabase.inTransaction(block: () -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    companion object {
        private const val REPO = "TheGreatRambler/mm2_level"
        private const val API = "https://huggingface.co/api/datasets/$REPO"
        private const val FILES = "https://huggingface.co/datasets/$REPO/resolve/main"

        /** Data id range covered by the archive (first and last level in the dataset). */
        const val MIN_DATA_ID = 3_000_004L
        const val MAX_DATA_ID = 35_899_543L

        val INDEX_COLUMNS = listOf(
            "data_id", "name", "gamestyle", "theme", "difficulty", "tag1", "tag2", "clear_rate",
            "likes", "plays", "clears", "attempts", "boos", "uploaded", "world_record",
        )

        @Volatile private var instance: LevelArchive? = null

        fun get(context: Context): LevelArchive =
            instance ?: synchronized(this) {
                instance ?: LevelArchive(context.applicationContext).also { instance = it }
            }
    }
}
