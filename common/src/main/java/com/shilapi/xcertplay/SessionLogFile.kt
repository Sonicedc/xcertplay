package com.shilapi.xcertplay

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

internal class SessionLogFile(val file: File) : Closeable {
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var closed = false

    fun reset(header: String) {
        synchronized(lock) {
            if (closed) return
            file.parentFile?.mkdirs()
            writer?.close()
            file.writeText("", StandardCharsets.UTF_8)
            writer = file.bufferedWriter(StandardCharsets.UTF_8).also {
                it.appendLine(header)
                it.flush()
            }
        }
    }

    fun append(line: String) {
        synchronized(lock) {
            if (closed) return
            val activeWriter = writer ?: return
            try {
                activeWriter.appendLine(line)
                activeWriter.flush()
            } catch (_: IOException) {
                runCatching { activeWriter.close() }
                writer = null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { writer?.close() }
            writer = null
        }
    }
}
