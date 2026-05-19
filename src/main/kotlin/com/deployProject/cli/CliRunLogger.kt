package com.deployProject.cli

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date

object CliRunLogger {
    data class Session(
        val logFile: File,
        private val originalOut: PrintStream,
        private val originalErr: PrintStream,
        private val teeOut: PrintStream,
        private val teeErr: PrintStream,
        private val logStream: PrintStream
    ) : AutoCloseable {
        override fun close() {
            teeOut.flush()
            teeErr.flush()
            logStream.flush()
            System.setOut(originalOut)
            System.setErr(originalErr)
            logStream.close()
        }
    }

    fun install(): Session? {
        val originalOut = System.out
        val originalErr = System.err

        return runCatching {
            val logDir = File(System.getProperty("user.dir"), "logs").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
            val logFile = File(logDir, "deploy-project-cli-$timestamp.log")
            val logStream = PrintStream(FileOutputStream(logFile, true), true, Charsets.UTF_8.name())
            val teeOut = PrintStream(TeeOutputStream(originalOut, logStream), true, Charsets.UTF_8.name())
            val teeErr = PrintStream(TeeOutputStream(originalErr, logStream), true, Charsets.UTF_8.name())

            System.setOut(teeOut)
            System.setErr(teeErr)
            println("[INFO] CLI log file: ${logFile.absolutePath}")

            Session(logFile, originalOut, originalErr, teeOut, teeErr, logStream)
        }.onFailure { error ->
            originalErr.println("[WARN] Failed to initialize CLI file logging: ${error.message}")
        }.getOrNull()
    }

    private class TeeOutputStream(
        private val first: OutputStream,
        private val second: OutputStream
    ) : OutputStream() {
        override fun write(b: Int) {
            first.write(b)
            second.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            first.write(b, off, len)
            second.write(b, off, len)
        }

        override fun flush() {
            first.flush()
            second.flush()
        }

        override fun close() {
            flush()
        }
    }
}
