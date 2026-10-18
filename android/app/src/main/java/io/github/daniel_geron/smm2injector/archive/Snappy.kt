package io.github.daniel_geron.smm2injector.archive

import java.io.IOException

/** Decompressor for raw Snappy blocks (the codec the archive's Parquet files use). */
object Snappy {
    fun decompress(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ByteArray {
        var pos = offset
        val end = offset + length

        // Preamble: uncompressed length as a varint.
        var outLen = 0
        var shift = 0
        while (true) {
            val b = input[pos++].toInt() and 0xFF
            outLen = outLen or ((b and 0x7F) shl shift)
            if (b < 0x80) break
            shift += 7
        }

        val out = ByteArray(outLen)
        var op = 0
        while (pos < end) {
            val tag = input[pos++].toInt() and 0xFF
            when (tag and 3) {
                0 -> { // literal
                    var len = tag ushr 2
                    if (len >= 60) {
                        val extra = len - 59
                        len = 0
                        for (i in 0 until extra) len = len or ((input[pos++].toInt() and 0xFF) shl (8 * i))
                    }
                    len += 1
                    System.arraycopy(input, pos, out, op, len)
                    pos += len
                    op += len
                }
                1 -> { // copy with 1-byte offset
                    val len = 4 + ((tag ushr 2) and 7)
                    val off = ((tag ushr 5) shl 8) or (input[pos++].toInt() and 0xFF)
                    op = copy(out, op, off, len)
                }
                2 -> { // copy with 2-byte offset
                    val len = (tag ushr 2) + 1
                    val off = (input[pos].toInt() and 0xFF) or ((input[pos + 1].toInt() and 0xFF) shl 8)
                    pos += 2
                    op = copy(out, op, off, len)
                }
                else -> { // copy with 4-byte offset
                    val len = (tag ushr 2) + 1
                    var off = 0
                    for (i in 0 until 4) off = off or ((input[pos + i].toInt() and 0xFF) shl (8 * i))
                    pos += 4
                    op = copy(out, op, off, len)
                }
            }
        }
        if (op != outLen) throw IOException("Corrupt Snappy data")
        return out
    }

    private fun copy(out: ByteArray, op: Int, offset: Int, len: Int): Int {
        if (offset <= 0 || offset > op) throw IOException("Corrupt Snappy data")
        // Copies may overlap their own output, so go byte by byte.
        var src = op - offset
        for (i in 0 until len) out[op + i] = out[src++]
        return op + len
    }
}
