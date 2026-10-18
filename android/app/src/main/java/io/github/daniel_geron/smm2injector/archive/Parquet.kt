package io.github.daniel_geron.smm2injector.archive

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Random access to a file's bytes, either on disk or over HTTP range requests. */
interface ByteSource : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class FileSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

/**
 * Reads a remote file with HTTP range requests. Hugging Face redirects file URLs to a signed CDN
 * URL; the redirect is followed automatically on every request.
 */
class HttpSource(private val url: String, override val size: Long) : ByteSource {
    /** Where [url] redirects to (Hugging Face's CDN). Signed, so it's re-resolved if it expires. */
    @Volatile private var resolved: String? = null

    override fun read(offset: Long, length: Int): ByteArray {
        var lastError: IOException? = null
        repeat(3) { attempt ->
            try {
                return readOnce(offset, length)
            } catch (e: IOException) {
                lastError = e
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw lastError!!
    }

    private fun resolve(): String {
        resolved?.let { return it }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val location = conn.getHeaderField("Location")
            return (if (conn.responseCode in 300..399 && location != null) URL(URL(url), location).toString() else url)
                .also { resolved = it }
        } finally {
            conn.disconnect()
        }
    }

    private fun readOnce(offset: Long, length: Int): ByteArray {
        val conn = URL(resolve()).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code == 403 || code == 410) resolved = null // signed CDN link expired
            if (code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Range request failed (HTTP $code)")
            }
            val out = ByteArray(length)
            conn.inputStream.use { input ->
                var read = 0
                while (read < length) {
                    val n = input.read(out, read, length - read)
                    if (n < 0) throw IOException("Connection closed early")
                    read += n
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    override fun close() {}

    companion object {
        const val USER_AGENT = "SMM2LevelInjector-Android"
    }
}

/** One column of one row group. */
class ColumnChunk(
    val name: String,
    val type: Int,
    val optional: Boolean,
    val codec: Int,
    val start: Long,
    val length: Long,
    val numValues: Long,
    val minStat: ByteArray?,
    val maxStat: ByteArray?,
)

class RowGroup(val numRows: Int, val columns: Map<String, ColumnChunk>)

/**
 * Minimal Parquet reader covering what the mm2_level dataset uses: flat schema, optional columns,
 * PLAIN and dictionary encodings, data pages v1/v2, Snappy/GZIP/uncompressed.
 */
class ParquetFile(val source: ByteSource) : Closeable {
    val rowGroups: List<RowGroup>
    val numRows: Long

    init {
        val tail = source.read(source.size - 8, 8)
        if (String(tail, 4, 4, Charsets.US_ASCII) != "PAR1") throw IOException("Not a Parquet file")
        val footerLen = le32(tail, 0)
        val footer = ThriftReader(source.read(source.size - 8 - footerLen, footerLen)).readStruct()
        numRows = footer.long(3) ?: 0

        // Leaf columns of the (flat) schema, in order.
        val leaves = footer.structs(2).drop(1).filter { it.int(5) == null }
        rowGroups = footer.structs(4).map { rg ->
            val chunks = rg.structs(1).mapIndexed { i, cc ->
                val md = cc.struct(3) ?: throw IOException("Missing column metadata")
                val dataOffset = md.long(9)!!
                val dictOffset = md.long(11)?.takeIf { it > 0 }
                val stats = md.struct(12)
                ColumnChunk(
                    name = md.strings(3).joinToString("."),
                    type = md.int(1)!!,
                    optional = leaves.getOrNull(i)?.int(3) == 1,
                    codec = md.int(4)!!,
                    start = if (dictOffset != null) minOf(dictOffset, dataOffset) else dataOffset,
                    length = md.long(7)!!,
                    numValues = md.long(5)!!,
                    minStat = stats?.bytes(6) ?: stats?.bytes(2),
                    maxStat = stats?.bytes(5) ?: stats?.bytes(1),
                )
            }
            RowGroup(rg.int(3)!!, chunks.associateBy { it.name })
        }
    }

    /** Reads every value of one column in one row group (nulls included). */
    fun readColumn(rowGroup: Int, column: String): List<Any?> = readColumns(rowGroup, listOf(column))[column]!!

    /**
     * Reads several columns of a row group. Column chunks that sit close together in the file are
     * fetched with a single read, which matters a lot over HTTP.
     */
    fun readColumns(rowGroup: Int, columns: List<String>): Map<String, List<Any?>> {
        val chunks = columns.map { rowGroups[rowGroup].columns[it] ?: throw IOException("No column $it") }
            .sortedBy { it.start }
        val result = HashMap<String, List<Any?>>()
        var i = 0
        while (i < chunks.size) {
            var j = i
            while (j + 1 < chunks.size && chunks[j + 1].start - (chunks[j].start + chunks[j].length) < MERGE_GAP) j++
            val spanStart = chunks[i].start
            val span = source.read(spanStart, (chunks[j].start + chunks[j].length - spanStart).toInt())
            for (k in i..j) {
                val c = chunks[k]
                result[c.name] = decodeChunk(rowGroup, c, span, (c.start - spanStart).toInt())
            }
            i = j + 1
        }
        return result
    }

    private fun decodeChunk(rowGroup: Int, chunk: ColumnChunk, bytes: ByteArray, from: Int): List<Any?> {
        val out = ArrayList<Any?>(rowGroups[rowGroup].numRows)
        var dictionary: List<Any?>? = null
        var pos = from
        val end = from + chunk.length.toInt()
        while (pos < end) {
            val reader = ThriftReader(bytes, pos)
            val header = reader.readStruct()
            val bodyStart = reader.pos
            val bodyLen = header.int(3)!!
            dictionary = decodePage(chunk, header, bytes, bodyStart, bodyLen, dictionary, out)
            pos = bodyStart + bodyLen
        }
        return out
    }

    /**
     * Reads a single value without downloading the whole column chunk: walks the page headers
     * and only fetches the page that holds [row] (plus the dictionary page if that page needs it).
     */
    fun readValue(rowGroup: Int, column: String, row: Int): Any? {
        val chunk = rowGroups[rowGroup].columns[column] ?: throw IOException("No column $column")
        val end = chunk.start + chunk.length
        var offset = chunk.start
        var firstRow = 0L
        var dictionary: List<Any?>? = null
        while (offset < end) {
            val (header, headerLen) = readPageHeader(offset, end)
            val bodyStart = offset + headerLen
            val bodyLen = header.int(3)!!
            val type = header.int(1)!!
            if (type == DICTIONARY_PAGE) {
                val body = source.read(bodyStart, bodyLen)
                dictionary = decodePage(chunk, header, body, 0, bodyLen, null, ArrayList())
            } else if (type == DATA_PAGE || type == DATA_PAGE_V2) {
                val pageValues = (header.struct(5) ?: header.struct(8))!!.long(1)!!
                if (row < firstRow + pageValues) {
                    val body = source.read(bodyStart, bodyLen)
                    val values = ArrayList<Any?>(pageValues.toInt())
                    decodePage(chunk, header, body, 0, bodyLen, dictionary, values)
                    return values[(row - firstRow).toInt()]
                }
                firstRow += pageValues
            }
            offset = bodyStart + bodyLen
        }
        throw IOException("Row $row not found in $column")
    }

    private fun readPageHeader(offset: Long, end: Long): Pair<ThriftStruct, Int> {
        var want = 256
        while (true) {
            val len = minOf(want.toLong(), end - offset).toInt()
            val buf = source.read(offset, len)
            try {
                val reader = ThriftReader(buf)
                return reader.readStruct() to reader.pos
            } catch (e: IOException) {
                if (len < want) throw e // already read to the end of the chunk
                want *= 4
            }
        }
    }

    /** Decodes one page into [out]; returns the dictionary (new or unchanged). */
    private fun decodePage(
        chunk: ColumnChunk,
        header: ThriftStruct,
        buf: ByteArray,
        start: Int,
        len: Int,
        dictionary: List<Any?>?,
        out: MutableList<Any?>,
    ): List<Any?>? {
        when (header.int(1)) {
            DICTIONARY_PAGE -> {
                val dh = header.struct(7)!!
                val data = decompress(chunk.codec, buf, start, len, header.int(2)!!)
                val values = ArrayList<Any?>(dh.int(1)!!)
                decodePlain(chunk.type, data, 0, dh.int(1)!!, values)
                return values
            }
            DATA_PAGE -> {
                val dh = header.struct(5)!!
                val numValues = dh.int(1)!!
                val data = decompress(chunk.codec, buf, start, len, header.int(2)!!)
                var p = 0
                val defined = if (chunk.optional) {
                    val defLen = le32(data, 0)
                    p = 4
                    RleDecoder(data, p, p + defLen, 1).readAll(numValues).also { p += defLen }
                } else null
                decodeValues(chunk.type, dh.int(2)!!, data, p, data.size, numValues, defined, dictionary, out)
            }
            DATA_PAGE_V2 -> {
                val dh = header.struct(8)!!
                val numValues = dh.int(1)!!
                val defLen = dh.int(5)!!
                val repLen = dh.int(6)!!
                val defined = if (chunk.optional) {
                    RleDecoder(buf, start + repLen, start + repLen + defLen, 1).readAll(numValues)
                } else null
                val valuesStart = start + repLen + defLen
                val compressed = dh.bool(7) ?: true
                val data = if (compressed) {
                    decompress(chunk.codec, buf, valuesStart, start + len - valuesStart, header.int(2)!! - repLen - defLen)
                } else buf.copyOfRange(valuesStart, start + len)
                decodeValues(chunk.type, dh.int(4)!!, data, 0, data.size, numValues, defined, dictionary, out)
            }
        }
        return dictionary
    }

    private fun decodeValues(
        type: Int,
        encoding: Int,
        data: ByteArray,
        start: Int,
        end: Int,
        numValues: Int,
        defined: IntArray?,
        dictionary: List<Any?>?,
        out: MutableList<Any?>,
    ) {
        val nonNull = defined?.count { it == 1 } ?: numValues
        val values = ArrayList<Any?>(nonNull)
        when (encoding) {
            PLAIN -> decodePlain(type, data, start, nonNull, values)
            PLAIN_DICTIONARY, RLE_DICTIONARY -> {
                val dict = dictionary ?: throw IOException("Dictionary page missing")
                val bitWidth = data[start].toInt() and 0xFF
                val idx = RleDecoder(data, start + 1, end, bitWidth).readAll(nonNull)
                for (i in idx) values.add(dict[i])
            }
            else -> throw IOException("Unsupported encoding $encoding")
        }
        if (defined == null) {
            out.addAll(values)
        } else {
            var v = 0
            for (d in defined) out.add(if (d == 1) values[v++] else null)
        }
    }

    private fun decodePlain(type: Int, data: ByteArray, start: Int, count: Int, out: MutableList<Any?>) {
        var p = start
        repeat(count) {
            out.add(when (type) {
                INT32 -> le32(data, p).also { p += 4 }
                INT64 -> le64(data, p).also { p += 8 }
                DOUBLE -> Double.fromBits(le64(data, p)).also { p += 8 }
                FLOAT -> Float.fromBits(le32(data, p)).also { p += 4 }
                BYTE_ARRAY -> {
                    val n = le32(data, p)
                    data.copyOfRange(p + 4, p + 4 + n).also { p += 4 + n }
                }
                else -> throw IOException("Unsupported column type $type")
            })
        }
    }

    private fun decompress(codec: Int, buf: ByteArray, start: Int, len: Int, uncompressedLen: Int): ByteArray =
        when (codec) {
            UNCOMPRESSED -> buf.copyOfRange(start, start + len)
            SNAPPY -> Snappy.decompress(buf, start, len)
            GZIP -> GZIPInputStream(ByteArrayInputStream(buf, start, len)).use { it.readBytes() }
            else -> throw IOException("Unsupported compression codec $codec")
        }.also {
            if (it.size != uncompressedLen) throw IOException("Unexpected page size")
        }

    override fun close() = source.close()

    companion object {
        /** Column chunks closer than this are fetched together. */
        private const val MERGE_GAP = 64 * 1024

        // Page types
        const val DATA_PAGE = 0
        const val DICTIONARY_PAGE = 2
        const val DATA_PAGE_V2 = 3

        // Encodings
        const val PLAIN = 0
        const val PLAIN_DICTIONARY = 2
        const val RLE_DICTIONARY = 8

        // Physical types
        const val INT32 = 1
        const val INT64 = 2
        const val FLOAT = 4
        const val DOUBLE = 5
        const val BYTE_ARRAY = 6

        // Codecs
        const val UNCOMPRESSED = 0
        const val SNAPPY = 1
        const val GZIP = 2

        fun le32(b: ByteArray, p: Int): Int =
            (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

        fun le64(b: ByteArray, p: Int): Long =
            (le32(b, p).toLong() and 0xFFFFFFFFL) or (le32(b, p + 4).toLong() shl 32)
    }
}

/** Parquet's RLE / bit-packed hybrid encoding. */
private class RleDecoder(private val buf: ByteArray, private var pos: Int, private val end: Int, private val bitWidth: Int) {
    fun readAll(count: Int): IntArray {
        val out = IntArray(count)
        var n = 0
        val byteWidth = (bitWidth + 7) / 8
        while (n < count && pos < end) {
            val header = readVarint()
            if (header and 1 == 0) {
                val runLen = header ushr 1
                var value = 0
                for (i in 0 until byteWidth) value = value or ((buf[pos++].toInt() and 0xFF) shl (8 * i))
                for (i in 0 until runLen) if (n < count) out[n++] = value
            } else {
                val numValues = (header ushr 1) * 8
                var bitPos = 0L
                val base = pos
                for (i in 0 until numValues) {
                    var value = 0
                    for (b in 0 until bitWidth) {
                        val bit = bitPos + b
                        val byte = buf[base + (bit ushr 3).toInt()].toInt() and 0xFF
                        value = value or (((byte ushr (bit and 7).toInt()) and 1) shl b)
                    }
                    bitPos += bitWidth
                    if (n < count) out[n++] = value
                }
                pos = base + ((header ushr 1) * bitWidth)
            }
        }
        if (n < count) throw IOException("Truncated RLE data")
        return out
    }

    private fun readVarint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }
}
