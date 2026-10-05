package com.easywhatsappbackup.easy_whatsapp_backup

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class FolderStats(
    val bytes: Long,
    val files: Int,
    val skipped: Int
)

/**
 * Computes SAF folder metadata without reading file contents.
 *
 * The primary path uses ContentResolver child-document queries because it is
 * much faster than recursively materializing DocumentFile objects for large
 * chat application media trees. A DocumentFile fallback remains for providers that do
 * not support direct document queries.
 */
class FolderStatsCalculator(private val context: Context) {
    fun calculateFolderStats(uri: Uri): FolderStats {
        return calculateWithDocumentQueries(uri) ?: calculateWithDocumentFile(uri)
    }

    fun availableBytesForUri(uri: Uri): Long? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        return try {
            val documentId = DocumentsContract.getDocumentId(uri)
            val separator = documentId.indexOf(':')
            if (separator <= 0) return null

            val volume = documentId.substring(0, separator)
            val relativePath = documentId.substring(separator + 1)
            if (volume != "primary") return null

            val base = Environment.getExternalStorageDirectory()
            val target = if (relativePath.isBlank()) base else File(base, relativePath)
            val statTarget = if (target.isDirectory) target else target.parentFile ?: base
            StatFs(statTarget.absolutePath).availableBytes
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateWithDocumentQueries(uri: Uri): FolderStats? {
        val authority = uri.authority ?: return null
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return null
        }

        return try {
            scanDocumentId(uri, authority, rootDocumentId)
        } catch (_: Exception) {
            null
        }
    }

    private fun scanDocumentId(treeUri: Uri, authority: String, documentId: String): FolderStats {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            documentId
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )

        var bytes = 0L
        var files = 0
        var skipped = 0

        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
            ?: return FolderStats(bytes = 0L, files = 0, skipped = 1)

        cursor.useSafely {
            val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

            while (it.moveToNext()) {
                val childDocumentId = it.getNullableString(idColumn)
                val mimeType = it.getNullableString(mimeColumn)
                if (childDocumentId == null || mimeType == null) {
                    skipped += 1
                    continue
                }

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val stats = scanDocumentId(treeUri, authority, childDocumentId)
                    bytes += stats.bytes
                    files += stats.files
                    skipped += stats.skipped
                } else {
                    bytes += it.getNullableLong(sizeColumn)?.coerceAtLeast(0L) ?: 0L
                    files += 1
                }
            }
        }

        return FolderStats(bytes = bytes, files = files, skipped = skipped)
    }

    private fun calculateWithDocumentFile(uri: Uri): FolderStats {
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: throw IllegalArgumentException("Could not open selected folder.")
        return scanDocumentFile(root)
    }

    private fun scanDocumentFile(document: DocumentFile): FolderStats {
        if (document.isFile) {
            val length = try {
                document.length().coerceAtLeast(0L)
            } catch (_: Exception) {
                return FolderStats(bytes = 0L, files = 0, skipped = 1)
            }
            return FolderStats(bytes = length, files = 1, skipped = 0)
        }

        if (!document.isDirectory) return FolderStats(bytes = 0L, files = 0, skipped = 1)

        val children = try {
            document.listFiles()
        } catch (_: Exception) {
            return FolderStats(bytes = 0L, files = 0, skipped = 1)
        }

        var bytes = 0L
        var files = 0
        var skipped = 0
        for (child in children) {
            val stats = scanDocumentFile(child)
            bytes += stats.bytes
            files += stats.files
            skipped += stats.skipped
        }
        return FolderStats(bytes = bytes, files = files, skipped = skipped)
    }

    private fun Cursor.useSafely(block: (Cursor) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun Cursor.getNullableString(index: Int): String? {
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.getNullableLong(index: Int): Long? {
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }
}
