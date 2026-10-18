package io.github.daniel_geron.smm2injector.archive

import java.io.IOException

/** A decoded Thrift struct: field id -> value (Long, Double, Boolean, ByteArray, List or ThriftStruct). */
class ThriftStruct(private val fields: Map<Int, Any?>) {
    fun long(id: Int): Long? = fields[id] as Long?
    fun int(id: Int): Int? = (fields[id] as Long?)?.toInt()
    fun bool(id: Int): Boolean? = fields[id] as Boolean?
    fun bytes(id: Int): ByteArray? = fields[id] as ByteArray?
    fun string(id: Int): String? = bytes(id)?.toString(Charsets.UTF_8)
    fun struct(id: Int): ThriftStruct? = fields[id] as ThriftStruct?

    @Suppress("UNCHECKED_CAST")
    fun structs(id: Int): List<ThriftStruct> = (fields[id] as List<ThriftStruct>?).orEmpty()

    @Suppress("UNCHECKED_CAST")
    fun strings(id: Int): List<String> =
        (fields[id] as List<ByteArray>?).orEmpty().map { it.toString(Charsets.UTF_8) }
}

/** Reader for the Thrift compact protocol, which Parquet uses for its metadata. */
class ThriftReader(private val buf: ByteArray, var pos: Int = 0) {

    fun readStruct(): ThriftStruct {
        val fields = HashMap<Int, Any?>()
        var lastId = 0
        while (true) {
            val header = readByte()
            if (header == 0) break
            val type = header and 0x0F
            val delta = header ushr 4
            val id = if (delta != 0) lastId + delta else zigzag(readVarint()).toInt()
            lastId = id
            fields[id] = when (type) {
                TRUE -> true
                FALSE -> false
                else -> readValue(type)
            }
        }
        return ThriftStruct(fields)
    }

    private fun readValue(type: Int): Any? = when (type) {
        TRUE, FALSE -> readByte() == 1
        BYTE -> readByte().toByte().toLong()
        I16, I32, I64 -> zigzag(readVarint())
        DOUBLE -> {
            var bits = 0L
            for (i in 0 until 8) bits = bits or ((readByte().toLong()) shl (8 * i))
            Double.fromBits(bits)
        }
        BINARY -> {
            val len = readVarint().toInt()
            if (len < 0 || pos + len > buf.size) throw IOException("Truncated Thrift data")
            buf.copyOfRange(pos, pos + len).also { pos += len }
        }
        LIST, SET -> {
            val header = readByte()
            val size = (header ushr 4).let { if (it == 15) readVarint().toInt() else it }
            val elemType = header and 0x0F
            List(size) { readValue(elemType) }
        }
        MAP -> {
            val size = readVarint().toInt()
            if (size == 0) emptyMap<Any?, Any?>() else {
                val types = readByte()
                (0 until size).associate { readValue(types ushr 4) to readValue(types and 0x0F) }
            }
        }
        STRUCT -> readStruct()
        else -> throw IOException("Unknown Thrift type $type")
    }

    private fun readByte(): Int {
        if (pos >= buf.size) throw IOException("Truncated Thrift data")
        return buf[pos++].toInt() and 0xFF
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = readByte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    private fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

    private companion object {
        const val TRUE = 1
        const val FALSE = 2
        const val BYTE = 3
        const val I16 = 4
        const val I32 = 5
        const val I64 = 6
        const val DOUBLE = 7
        const val BINARY = 8
        const val LIST = 9
        const val SET = 10
        const val MAP = 11
        const val STRUCT = 12
    }
}
