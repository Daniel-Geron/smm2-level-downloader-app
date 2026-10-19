package io.github.daniel_geron.smm2injector.archive

import java.io.Closeable
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock

/**
 * A small SQLite wrapper with the same shape as Android's SQLiteDatabase, so the archive code reads
 * the same on both. One connection is shared; a transaction or open cursor holds it for its thread.
 */
class Db(file: File) : Closeable {
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private val lock = ReentrantLock()

    fun execSQL(sql: String, args: Array<out Any?> = emptyArray()) = locked {
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.execute()
        }
    }

    /** Runs a query; the returned cursor keeps the database to this thread until it's closed. */
    fun rawQuery(sql: String, args: Array<String>?): Cursor {
        lock.lock()
        try {
            val st = conn.prepareStatement(sql)
            args?.forEachIndexed { i, a -> st.setString(i + 1, a) }
            return Cursor(st, st.executeQuery())
        } catch (e: Throwable) {
            lock.unlock()
            throw e
        }
    }

    /** A statement for use inside [inTransaction]. */
    fun prepare(sql: String): PreparedStatement = conn.prepareStatement(sql)

    fun count(sql: String): Long = rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    fun inTransaction(block: () -> Unit) = locked {
        conn.autoCommit = false
        try {
            block()
            conn.commit()
        } catch (e: Throwable) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    override fun close() = locked { conn.close() }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Column indexes are 0-based, as on Android. */
    inner class Cursor(private val st: PreparedStatement, private val rs: ResultSet) : Closeable {
        private var started = false

        fun moveToNext(): Boolean {
            started = true
            return rs.next()
        }

        fun moveToFirst(): Boolean = if (started) false else moveToNext()

        fun getInt(i: Int): Int = rs.getInt(i + 1)
        fun getLong(i: Int): Long = rs.getLong(i + 1)
        fun getDouble(i: Int): Double = rs.getDouble(i + 1)
        fun getString(i: Int): String = rs.getString(i + 1) ?: ""

        override fun close() {
            try {
                rs.close()
                st.close()
            } finally {
                lock.unlock()
            }
        }
    }
}
