package com.easywhatsappbackup.easy_whatsapp_backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class SafHelper(private val context: Context) {
    private val prefs = context.getSharedPreferences("easy_whatsapp_backup_saf", Context.MODE_PRIVATE)

    var sourceFolderUri: String?
        get() = prefs.getString("sourceFolderUri", null)
        set(value) = prefs.edit().putString("sourceFolderUri", value).apply()

    var outputTarUri: String?
        get() = prefs.getString("outputTarUri", null)
        set(value) = prefs.edit().putString("outputTarUri", value).apply()

    var inputTarUri: String?
        get() = prefs.getString("inputTarUri", null)
        set(value) = prefs.edit().putString("inputTarUri", value).apply()

    var extractFolderUri: String?
        get() = prefs.getString("extractFolderUri", null)
        set(value) = prefs.edit().putString("extractFolderUri", value).apply()

    fun cachedFolderStats(uri: String): FolderStats? {
        val cachedUri = prefs.getString("folderStatsUri", null) ?: return null
        if (cachedUri != uri) return null
        if (!prefs.contains("folderStatsBytes")) return null
        return FolderStats(
            bytes = prefs.getLong("folderStatsBytes", 0L),
            files = prefs.getInt("folderStatsFiles", 0),
            skipped = prefs.getInt("folderStatsSkipped", 0)
        )
    }

    fun cacheFolderStats(uri: String, stats: FolderStats) {
        prefs.edit()
            .putString("folderStatsUri", uri)
            .putLong("folderStatsBytes", stats.bytes)
            .putInt("folderStatsFiles", stats.files)
            .putInt("folderStatsSkipped", stats.skipped)
            .apply()
    }

    fun persistUriPermission(uri: Uri, resultFlags: Int, forceWrite: Boolean) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        var flags = resultFlags and (read or write)
        if (forceWrite) flags = flags or write

        if (flags != 0) {
            // Persist the user's SAF grant so Android/media trees remain usable after app restart.
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    fun treeDocument(uri: Uri): DocumentFile {
        return DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalArgumentException("Could not open the selected folder.")
    }

    fun displayName(uri: Uri): String {
        return DocumentFile.fromSingleUri(context, uri)?.name
            ?: DocumentFile.fromTreeUri(context, uri)?.name
            ?: uri.lastPathSegment
            ?: uri.toString()
    }

    fun childDirectory(parent: DocumentFile, name: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null) return if (existing.isDirectory) existing else null
        return parent.createDirectory(name)
    }

    fun childFile(parent: DocumentFile, name: String): DocumentFile? {
        val existing = parent.findFile(name)
        if (existing != null) {
            if (existing.isFile) existing.delete() else return null
        }
        return parent.createFile("application/octet-stream", name)
    }
}
