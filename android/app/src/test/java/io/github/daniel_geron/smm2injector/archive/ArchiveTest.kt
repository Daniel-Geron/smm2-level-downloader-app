package io.github.daniel_geron.smm2injector.archive

import java.io.File
import java.security.MessageDigest
import java.util.zip.Inflater
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class CourseCodeTest {
    @Test
    fun knownCodes() {
        assertEquals(60665284L, CourseCode.toDataId("HWV-VLH-WCG"))
        assertEquals(6635842L, CourseCode.toDataId("2J53K2Y9G"))
        assertEquals("HWVVLHWCG", CourseCode.fromDataId(60665284))
        assertEquals("4RFXV8WCG", CourseCode.fromDataId(3000004))
        for (id in listOf(3000004L, 3091313L, 12345678L, 60665284L, 99999999L)) {
            assertEquals(id, CourseCode.toDataId(CourseCode.fromDataId(id)))
        }
        assertNull(CourseCode.toDataId("AAAAAAAAA"))
        assertNull(CourseCode.toDataId("HWVVLHWCH")) // bad checksum
    }
}

/** These use real files; run with -PtestDataDir=<folder>. */
class ArchiveFileTest {
    private val dir = File(System.getProperty("testDataDir").orEmpty())

    private fun file(name: String): File {
        val f = File(dir, name)
        assumeTrue("test data $name not available", f.exists())
        return f
    }

    @Test
    fun decryptsRealCourseFiles() {
        for (name in listOf("preloaded_000.bcd", "api_HWVVLHWCG.bcd", "python_encrypted_3000004.bcd")) {
            val decrypted = CourseCrypto.decrypt(file(name).readBytes())
            assertEquals(CourseCrypto.DECRYPTED_SIZE, decrypted.size)
            val again = CourseCrypto.decrypt(CourseCrypto.encrypt(decrypted))
            assertArrayEquals(decrypted, again)
        }
    }

    @Test
    fun readsParquetLikePyarrow() {
        val ref = JSONObject(file("reference.json").readText())
        ParquetFile(FileSource(file("shard0.parquet"))).use { pq ->
            assertEquals(ref.getLong("num_rows"), pq.numRows)
            assertEquals(ref.getInt("row_groups"), pq.rowGroups.size)
            val groups = ref.getJSONObject("groups")
            for (rgKey in groups.keys()) {
                val rg = rgKey.toInt()
                val g = groups.getJSONObject(rgKey)
                val ids = pq.readColumn(rg, "data_id")
                assertEquals(g.getInt("num_rows"), ids.size)
                assertEquals(g.getLong("data_id_sum"), ids.sumOf { (it as Int).toLong() })
                assertEquals(g.getLong("likes_sum"), pq.readColumn(rg, "likes").sumOf { ((it as Int?) ?: 0).toLong() })
                assertEquals(g.getLong("tag1_sum"), pq.readColumn(rg, "tag1").sumOf { ((it as Int?) ?: 0).toLong() })
                assertEquals(g.getDouble("clear_rate_sum"), pq.readColumn(rg, "clear_rate").sumOf { (it as Double?) ?: 0.0 }, 1e-6)
                val names = pq.readColumn(rg, "name").joinToString("\n") { (it as ByteArray?)?.toString(Charsets.UTF_8) ?: "" }
                assertEquals(g.getString("name_md5"), md5(names.toByteArray()))
                assertEquals(g.getInt("pid_nulls"), pq.readColumn(rg, "uploader_pid").count { it == null })

                val levelData = pq.readColumn(rg, "level_data")
                val rows = g.getJSONObject("rows")
                for (rowKey in rows.keys()) {
                    val row = rowKey.toInt()
                    val r = rows.getJSONObject(rowKey)
                    assertEquals(r.getLong("data_id"), (ids[row] as Int).toLong())
                    val single = pq.readValue(rg, "level_data", row) as ByteArray
                    assertEquals(r.getString("level_data_md5"), md5(single))
                    assertArrayEquals(levelData[row] as ByteArray, single)

                    // The archive stores the decrypted course zlib-compressed; it must re-encrypt cleanly.
                    val course = inflate(single)
                    assertEquals(CourseCrypto.DECRYPTED_SIZE, course.size)
                    assertArrayEquals(course, CourseCrypto.decrypt(CourseCrypto.encrypt(course)))
                }
            }
            println("Parquet reader matches pyarrow on ${groups.length()} row groups")
        }
    }

    @Test
    fun writesCourseForInGameTest() {
        // Writes a Kotlin-encrypted course file so it can be checked in the real game.
        ParquetFile(FileSource(file("shard0.parquet"))).use { pq ->
            val ids = pq.readColumn(7, "data_id")
            val row = ids.indexOf(3097825)
            val course = inflate(pq.readValue(7, "level_data", row) as ByteArray)
            File(dir, "kotlin_encrypted_3097825.bcd").writeBytes(CourseCrypto.encrypt(course))
            println("3097825 = ${CourseCode.fromDataId(3097825)}")
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArray(CourseCrypto.DECRYPTED_SIZE + 1)
        val n = inflater.inflate(out)
        inflater.end()
        return out.copyOf(n)
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
}

/** Talks to Hugging Face; run with -PtestDataDir=... (any value) to enable. */
class RemoteArchiveTest {
    @Test
    fun readsRemoteFileWithRangeRequests() {
        assumeTrue(System.getProperty("testDataDir").orEmpty().isNotEmpty())
        val base = "https://huggingface.co/datasets/TheGreatRambler/mm2_level/resolve/main/data/"
        val first = "train-00000-of-00196-7a2d43e1e8287c30.parquet" to 446200639L
        ParquetFile(HttpSource(base + first.first, first.second)).use { pq ->
            val ids = pq.readColumn(7, "data_id")
            val row = ids.indexOf(3097825)
            val remote = pq.readValue(7, "level_data", row) as ByteArray
            val local = File(System.getProperty("testDataDir"), "shard0.parquet")
            if (local.exists()) {
                ParquetFile(FileSource(local)).use { assertArrayEquals(it.readValue(7, "level_data", row) as ByteArray, remote) }
            }
            println("remote read OK: ${remote.size} bytes for 3097825")
        }
    }

    @Test
    fun printsArchiveDataIdRange() {
        assumeTrue(System.getProperty("testDataDir").orEmpty().isNotEmpty())
        val tree = org.json.JSONArray(java.net.URL("https://huggingface.co/api/datasets/TheGreatRambler/mm2_level/tree/main/data").readText())
        val files = (0 until tree.length()).map { tree.getJSONObject(it) }.sortedBy { it.getString("path") }
        val last = files.last()
        ParquetFile(HttpSource("https://huggingface.co/datasets/TheGreatRambler/mm2_level/resolve/main/" + last.getString("path"), last.getLong("size"))).use { pq ->
            val max = pq.rowGroups.last().columns["data_id"]!!.maxStat!!.let { ParquetFile.le32(it, 0) }
            println("archive: ${files.size} files, last file ${last.getString("path")}, max data_id $max (${CourseCode.fromDataId(max.toLong())})")
        }
    }
}
