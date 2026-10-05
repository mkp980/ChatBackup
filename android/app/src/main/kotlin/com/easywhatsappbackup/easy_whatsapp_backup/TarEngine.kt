package com.easywhatsappbackup.easy_whatsapp_backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream

class TarEngine(
    private val context: Context,
    private val progress: (Map<String, Any?>) -> Unit,
    private val isCancelled: () -> Boolean = { false }
) {
    private val bufferSize = 1024 * 1024
    private val safHelper = SafHelper(context)

    fun packToTar(sourceTreeUri: Uri, outputTarUri: Uri): Map<String, Any> {
        throwIfCancelled()
        val source = safHelper.treeDocument(sourceTreeUri)
        if (!source.canRead()) throw IllegalArgumentException("Cannot read the selected source folder.")

        // SAF trees such as Android/media can contain thousands of files, and a
        // full pre-count can look frozen before any TAR bytes are written.
        // Pack immediately and report an indeterminate total instead.
        val totalFiles: Int? = null
        var processedFiles = 0
        var bytesProcessed = 0L
        var skippedFiles = 0
        val skippedNames = mutableListOf<String>()

        val output = context.contentResolver.openOutputStream(outputTarUri, "w")
            ?: throw IllegalArgumentException("Cannot write to the selected TAR file.")

        BufferedOutputStream(output, bufferSize).use { buffered ->
            TarArchiveOutputStream(buffered).use { tarOut ->
                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                tarOut.setAddPaxHeadersForNonAsciiNames(true)

                progressEvent("packing", "", processedFiles, totalFiles, bytesProcessed, skippedFiles)
                writeDocument(source, "", tarOut) { event ->
                    when (event) {
                        is PackEvent.FileStarted -> {
                            progressEvent("packing", event.path, processedFiles, totalFiles, bytesProcessed, skippedFiles)
                        }
                        is PackEvent.FileFinished -> {
                            processedFiles += 1
                            bytesProcessed += event.bytes
                            progressEvent("packing", event.path, processedFiles, totalFiles, bytesProcessed, skippedFiles)
                        }
                        is PackEvent.FileSkipped -> {
                            skippedFiles += 1
                            skippedNames += event.path
                            progressEvent("packing", event.path, processedFiles, totalFiles, bytesProcessed, skippedFiles)
                        }
                    }
                }
            }
        }

        progressEvent("completed", "", processedFiles, totalFiles, bytesProcessed, skippedFiles)
        return summary("Completed", processedFiles, totalFiles ?: processedFiles, bytesProcessed, skippedFiles, skippedNames)
    }

    private fun writeDocument(
        document: DocumentFile,
        relativePath: String,
        tarOut: TarArchiveOutputStream,
        event: (PackEvent) -> Unit
    ) {
        throwIfCancelled()
        if (document.isDirectory) {
            val folderPath = if (relativePath.isBlank()) "" else ensureTrailingSlash(relativePath)
            if (folderPath.isNotBlank()) {
                val entry = TarArchiveEntry(folderPath)
                tarOut.putArchiveEntry(entry)
                tarOut.closeArchiveEntry()
            }

            val children = try {
                document.listFiles()
            } catch (_: Exception) {
                emptyArray()
            }
            for (child in children) {
                val childName = sanitizeArchiveName(child.name)
                if (childName == null) {
                    event(PackEvent.FileSkipped(relativePath))
                    continue
                }
                val childPath = if (relativePath.isBlank()) childName else "$relativePath/$childName"
                writeDocument(child, childPath, tarOut, event)
            }
            return
        }

        if (!document.isFile || !document.canRead()) {
            event(PackEvent.FileSkipped(relativePath))
            return
        }

        try {
            throwIfCancelled()
            event(PackEvent.FileStarted(relativePath))
            val size = fileSize(document)
            if (size < 0L) {
                event(PackEvent.FileSkipped(relativePath))
                return
            }
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IllegalArgumentException("Cannot open input stream.")
            val entry = TarArchiveEntry(relativePath)
            entry.size = size
            tarOut.putArchiveEntry(entry)
            val copied = input.use { it.copyToCounting(tarOut, bufferSize) }
            tarOut.closeArchiveEntry()
            event(PackEvent.FileFinished(relativePath, copied))
        } catch (error: Exception) {
            if (error is TarCancelledException) throw error
            try {
                tarOut.closeArchiveEntry()
            } catch (_: Exception) {
            }
            event(PackEvent.FileSkipped(relativePath))
        }
    }

    private fun fileSize(document: DocumentFile): Long {
        val documentLength = document.length()
        if (documentLength > 0L) return documentLength
        return try {
            context.contentResolver.openAssetFileDescriptor(document.uri, "r")?.use { descriptor ->
                if (descriptor.length >= 0L) descriptor.length else documentLength
            } ?: documentLength
        } catch (_: Exception) {
            documentLength
        }
    }

    fun extractTar(inputTarUri: Uri, destinationTreeUri: Uri): Map<String, Any> {
        throwIfCancelled()
        val destination = safHelper.treeDocument(destinationTreeUri)
        if (!destination.canWrite()) throw IllegalArgumentException("Cannot write to the selected extract folder.")

        var processedFiles = 0
        var processedEntries = 0
        var bytesProcessed = 0L
        var skippedFiles = 0
        val skippedNames = mutableListOf<String>()

        val input = context.contentResolver.openInputStream(inputTarUri)
            ?: throw IllegalArgumentException("Cannot read the selected TAR file.")

        BufferedInputStream(input, bufferSize).use { buffered ->
            TarArchiveInputStream(buffered).use { tarIn ->
                progressEvent("extracting", "", processedFiles, null, bytesProcessed, skippedFiles)
                while (true) {
                    throwIfCancelled()
                    val entry = tarIn.nextTarEntry ?: break
                    processedEntries += 1
                    val safeParts = sanitizeEntryPath(entry.name)
                    if (safeParts == null) {
                        skippedFiles += 1
                        skippedNames += entry.name
                        continue
                    }

                    progressEvent("extracting", entry.name, processedFiles, null, bytesProcessed, skippedFiles)
                    try {
                        if (entry.isDirectory) {
                            ensureDirectory(destination, safeParts)
                        } else {
                            val copied = extractFile(destination, safeParts, tarIn)
                            processedFiles += 1
                            bytesProcessed += copied
                        }
                    } catch (_: Exception) {
                        skippedFiles += 1
                        skippedNames += entry.name
                    }
                    progressEvent("extracting", entry.name, processedFiles, null, bytesProcessed, skippedFiles)
                }
            }
        }

        progressEvent("completed", "", processedFiles, processedEntries, bytesProcessed, skippedFiles)
        return summary("Completed", processedFiles, processedEntries, bytesProcessed, skippedFiles, skippedNames)
    }

    private fun extractFile(root: DocumentFile, parts: List<String>, input: InputStream): Long {
        throwIfCancelled()
        val fileName = parts.last()
        val parent = ensureDirectory(root, parts.dropLast(1))
        val outputFile = safHelper.childFile(parent, fileName)
            ?: throw IllegalArgumentException("Cannot create output file $fileName.")
        val output = context.contentResolver.openOutputStream(outputFile.uri, "w")
            ?: throw IllegalArgumentException("Cannot open output stream $fileName.")
        return output.use { input.copyToCounting(it, bufferSize) }
    }

    private fun ensureDirectory(root: DocumentFile, parts: List<String>): DocumentFile {
        throwIfCancelled()
        var current = root
        for (part in parts) {
            throwIfCancelled()
            current = safHelper.childDirectory(current, part)
                ?: throw IllegalArgumentException("Cannot create folder $part.")
        }
        return current
    }

    private fun countReadableFiles(root: DocumentFile): Int {
        throwIfCancelled()
        if (root.isFile) return if (root.canRead()) 1 else 0
        if (!root.isDirectory) return 0
        return try {
            root.listFiles().sumOf { countReadableFiles(it) }
        } catch (_: Exception) {
            0
        }
    }

    private fun sanitizeArchiveName(name: String?): String? {
        val cleaned = name?.replace('\\', '/')?.trim('/') ?: return null
        if (cleaned.isBlank()) return null
        if (cleaned == "." || cleaned == ".." || cleaned.contains('/')) return null
        return cleaned
    }

    private fun sanitizeEntryPath(path: String?): List<String>? {
        val normalized = path?.replace('\\', '/')?.trim('/') ?: return null
        if (normalized.isBlank()) return emptyList()
        if (normalized.startsWith("/") || normalized.contains(":")) return null
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.any { it == "." || it == ".." }) return null
        return parts
    }

    private fun ensureTrailingSlash(value: String): String {
        return if (value.endsWith("/")) value else "$value/"
    }

    private fun InputStream.copyToCounting(output: OutputStream, bufferSize: Int): Long {
        val buffer = ByteArray(bufferSize)
        var bytesCopied = 0L
        while (true) {
            throwIfCancelled()
            val bytes = read(buffer)
            if (bytes < 0) break
            output.write(buffer, 0, bytes)
            bytesCopied += bytes
        }
        return bytesCopied
    }

    private fun progressEvent(
        phase: String,
        currentFile: String,
        processedFiles: Int,
        totalFiles: Int?,
        bytesProcessed: Long,
        skippedFiles: Int
    ) {
        progress(
            mapOf(
                "phase" to phase,
                "currentFile" to currentFile,
                "processedFiles" to processedFiles,
                "totalFiles" to totalFiles,
                "bytesProcessed" to bytesProcessed,
                "skippedFiles" to skippedFiles,
                "status" to null,
                "message" to null
            )
        )
    }

    private fun summary(
        status: String,
        processedFiles: Int,
        totalFiles: Int,
        bytesProcessed: Long,
        skippedFiles: Int,
        skippedNames: List<String>
    ): Map<String, Any> {
        return mapOf(
            "status" to status,
            "processedFiles" to processedFiles,
            "totalFiles" to totalFiles,
            "bytesProcessed" to bytesProcessed,
            "skippedFiles" to skippedFiles,
            "skippedNames" to skippedNames.take(20)
        )
    }

    private fun throwIfCancelled() {
        if (isCancelled()) throw TarCancelledException()
    }

    private sealed class PackEvent {
        data class FileStarted(val path: String) : PackEvent()
        data class FileFinished(val path: String, val bytes: Long) : PackEvent()
        data class FileSkipped(val path: String) : PackEvent()
    }
}
