package io.github.daniel_geron.smm2injector.archive

import java.io.IOException
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption of Super Mario Maker 2 course files (course_data_XXX.bcd).
 *
 * Format and key table from MariOver's SMM2/encryption.py (TheGreatRambler, MIT); key generation
 * matches smmdb (Tarnadas, MIT) and Kinnay's NintendoClients (MIT).
 *
 * A course file is: 0x10-byte header | AES-128-CBC encrypted course | IV | random seed | AES-CMAC.
 * The AES and CMAC keys are derived from the seed with sead::Random and a fixed key table.
 */
object CourseCrypto {
    const val DECRYPTED_SIZE = 0x5BFC0
    const val FILE_SIZE = 0x10 + DECRYPTED_SIZE + 0x30

    private val random = SecureRandom()

    fun encrypt(decrypted: ByteArray): ByteArray {
        require(decrypted.size == DECRYPTED_SIZE) { "A course is $DECRYPTED_SIZE bytes, got ${decrypted.size}" }
        val seed = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val rand = SeadRandom(seed)
        val aesKey = createKey(rand)
        val cmacKey = createKey(rand)

        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            doFinal(decrypted)
        }
        val crc = CRC32().apply { update(decrypted) }.value
        val header = ByteArray(0x10).also {
            putLe32(it, 0, 1)            // version
            putLe16(it, 4, 0x10)         // file type
            putLe16(it, 6, 1)
            putLe32(it, 8, crc.toInt())
            "SCDL".toByteArray(Charsets.US_ASCII).copyInto(it, 12)
        }
        return header + encrypted + iv + seed + cmac(cmacKey, decrypted)
    }

    /** Decrypts a course file and verifies its CMAC; throws if the file is damaged. */
    fun decrypt(file: ByteArray): ByteArray {
        if (file.size != FILE_SIZE) throw IOException("Not a course file")
        val footer = 0x10 + DECRYPTED_SIZE
        val iv = file.copyOfRange(footer, footer + 16)
        val rand = SeadRandom(file.copyOfRange(footer + 16, footer + 32))
        val aesKey = createKey(rand)
        val cmacKey = createKey(rand)
        val decrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            doFinal(file, 0x10, DECRYPTED_SIZE)
        }
        if (!cmac(cmacKey, decrypted).contentEquals(file.copyOfRange(footer + 32, footer + 48))) {
            throw IOException("Course file checksum doesn't match")
        }
        return decrypted
    }

    private fun createKey(rand: SeadRandom): ByteArray {
        val key = ByteArray(16)
        for (i in 0 until 4) {
            var value = 0
            for (j in 0 until 4) {
                val index = rand.next() ushr 26
                val shift = (rand.next() ushr 27) and 0x18
                value = (value shl 8) or ((KEY_TABLE[index] ushr shift).toInt() and 0xFF)
            }
            putLe32(key, i * 4, value)
        }
        return key
    }

    /** AES-CMAC (RFC 4493) for input that is a whole number of blocks. */
    private fun cmac(key: ByteArray, data: ByteArray): ByteArray {
        require(data.size % 16 == 0)
        val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val k1 = shiftLeftXor(ecb.doFinal(ByteArray(16)))
        val msg = data.copyOf()
        for (i in 0 until 16) msg[msg.size - 16 + i] = (msg[msg.size - 16 + i].toInt() xor k1[i].toInt()).toByte()
        val cbc = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            doFinal(msg)
        }
        return cbc.copyOfRange(cbc.size - 16, cbc.size)
    }

    private fun shiftLeftXor(block: ByteArray): ByteArray {
        val out = ByteArray(16)
        var carry = 0
        for (i in 15 downTo 0) {
            val b = block[i].toInt() and 0xFF
            out[i] = ((b shl 1) or carry).toByte()
            carry = b ushr 7
        }
        if (block[0].toInt() and 0x80 != 0) out[15] = (out[15].toInt() xor 0x87).toByte()
        return out
    }

    /** sead::Random, Nintendo's xorshift128 generator. */
    private class SeadRandom(seed: ByteArray) {
        private val s = IntArray(4) { le32(seed, it * 4) }

        init {
            if (s.all { it == 0 }) {
                s[0] = 1; s[1] = 0x6C078967; s[2] = 0x714ACB41; s[3] = 0x48077044
            }
        }

        fun next(): Int {
            var t = s[0] xor (s[0] shl 11)
            t = t xor (t ushr 8) xor s[3] xor (s[3] ushr 19)
            s[0] = s[1]; s[1] = s[2]; s[2] = s[3]; s[3] = t
            return t
        }
    }

    private fun le32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    private fun putLe32(b: ByteArray, p: Int, v: Int) {
        for (i in 0 until 4) b[p + i] = (v ushr (8 * i)).toByte()
    }

    private fun putLe16(b: ByteArray, p: Int, v: Int) {
        b[p] = v.toByte(); b[p + 1] = (v ushr 8).toByte()
    }

    private val KEY_TABLE = longArrayOf(
        0x7AB1C9D2, 0xCA750936, 0x3003E59C, 0xF261014B, 0x2E25160A, 0xED614811, 0xF1AC6240,
        0xD59272CD, 0xF38549BF, 0x6CF5B327, 0xDA4DB82A, 0x820C435A, 0xC95609BA, 0x19BE08B0,
        0x738E2B81, 0xED3C349A, 0x045275D1, 0xE0A73635, 0x1DEBF4DA, 0x9924B0DE, 0x6A1FC367,
        0x71970467, 0xFC55ABEB, 0x368D7489, 0x0CC97D1D, 0x17CC441E, 0x3528D152, 0xD0129B53,
        0xE12A69E9, 0x13D1BDB7, 0x32EAA9ED, 0x42F41D1B, 0xAEA5F51F, 0x42C5D23C, 0x7CC742ED,
        0x723BA5F9, 0xDE5B99E3, 0x2C0055A4, 0xC38807B4, 0x4C099B61, 0xC4E4568E, 0x8C29C901,
        0xE13B34AC, 0xE7C3F212, 0xB67EF941, 0x08038965, 0x8AFD1E6A, 0x8E5341A3, 0xA4C61107,
        0xFBAF1418, 0x9B05EF64, 0x3C91734E, 0x82EC6646, 0xFB19F33E, 0x3BDE6FE2, 0x17A84CCA,
        0xCCDF0CE9, 0x50E4135C, 0xFF2658B2, 0x3780F156, 0x7D8F5D68, 0x517CBED1, 0x1FCDDF0D,
        0x77A58C94
    )
}
