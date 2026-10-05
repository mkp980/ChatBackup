package com.easywhatsappbackup.easy_whatsapp_backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder

class TarForegroundService : Service() {
    private val notificationId = 42
    private val channelId = "fast_tar_progress"
    private val channelName = "Fast TAR Progress"

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelRequested = true
                sendEvent("cancelling", "Cancelling after the current stream closes...", "", 0, null, 0, 0, "Cancelling")
                updateNotification("Cancelling", "Stopping after the current stream closes.", 0, null)
                return START_NOT_STICKY
            }
            ACTION_PACK, ACTION_EXTRACT -> startOperation(intent)
        }
        return START_NOT_STICKY
    }

    private fun startOperation(intent: Intent) {
        if (isRunning) {
            sendEvent("failed", "Another TAR task is already running.", "", 0, null, 0, 0)
            stopSelf()
            return
        }

        cancelRequested = false
        isRunning = true

        val operation = if (intent.action == ACTION_PACK) "Packing" else "Extracting"
        val initialNotification = buildNotification(operation, "Preparing selected SAF documents.", "", 0, null, true)
        sendEvent("starting", "$operation started. Opening selected SAF files...", "", 0, null, 0, 0, "Started")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(notificationId, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(notificationId, initialNotification)
            }
        } catch (error: Exception) {
            isRunning = false
            cancelRequested = false
            sendEvent("failed", error.message ?: "Could not start foreground service.", "", 0, null, 0, 0, "Failed")
            stopSelf()
            return
        }

        worker = Thread {
            runOperation(intent, operation)
        }.also { it.start() }
    }

    private fun runOperation(intent: Intent, operation: String) {
        var skippedFiles = 0
        var processedFiles = 0
        var totalFiles: Int? = null
        var bytesProcessed = 0L

        try {
            val engine = TarEngine(
                context = this,
                progress = { event ->
                    val phase = (event["phase"] as? String).orEmpty()
                    val currentFile = (event["currentFile"] as? String).orEmpty()
                    processedFiles = (event["processedFiles"] as? Number)?.toInt() ?: processedFiles
                    totalFiles = (event["totalFiles"] as? Number)?.toInt()
                    bytesProcessed = (event["bytesProcessed"] as? Number)?.toLong() ?: bytesProcessed
                    skippedFiles = (event["skippedFiles"] as? Number)?.toInt() ?: skippedFiles

                    sendEvent(phase, null, currentFile, processedFiles, totalFiles, bytesProcessed, skippedFiles)
                    updateNotification(operation, currentFile, processedFiles, totalFiles)
                },
                isCancelled = { cancelRequested }
            )

            val summary = if (intent.action == ACTION_PACK) {
                val sourceUri = intent.getStringExtra(EXTRA_SOURCE_URI)
                    ?: throw IllegalArgumentException("Missing source folder URI.")
                val outputUri = intent.getStringExtra(EXTRA_OUTPUT_URI)
                    ?: throw IllegalArgumentException("Missing output TAR URI.")
                engine.packToTar(Uri.parse(sourceUri), Uri.parse(outputUri))
            } else {
                val tarUri = intent.getStringExtra(EXTRA_TAR_URI)
                    ?: throw IllegalArgumentException("Missing TAR file URI.")
                val destinationUri = intent.getStringExtra(EXTRA_DESTINATION_URI)
                    ?: throw IllegalArgumentException("Missing extract folder URI.")
                engine.extractTar(Uri.parse(tarUri), Uri.parse(destinationUri))
            }

            skippedFiles = (summary["skippedFiles"] as? Number)?.toInt() ?: skippedFiles
            processedFiles = (summary["processedFiles"] as? Number)?.toInt() ?: processedFiles
            totalFiles = (summary["totalFiles"] as? Number)?.toInt() ?: totalFiles
            bytesProcessed = (summary["bytesProcessed"] as? Number)?.toLong() ?: bytesProcessed
            finishOperation("Completed", operation, "Completed with $skippedFiles skipped file(s).", processedFiles, totalFiles, bytesProcessed, skippedFiles)
        } catch (_: TarCancelledException) {
            finishOperation("Cancelled", operation, "Cancelled. Partial output was left as-is.", processedFiles, totalFiles, bytesProcessed, skippedFiles)
        } catch (error: Exception) {
            finishOperation("Failed", operation, error.message ?: "TAR operation failed.", processedFiles, totalFiles, bytesProcessed, skippedFiles)
        }
    }

    private fun finishOperation(
        status: String,
        operation: String,
        message: String,
        processedFiles: Int,
        totalFiles: Int?,
        bytesProcessed: Long,
        skippedFiles: Int
    ) {
        sendEvent(status.lowercase(), message, "", processedFiles, totalFiles, bytesProcessed, skippedFiles, status)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        manager.cancel(notificationId)
        isRunning = false
        cancelRequested = false
        stopSelf()
    }

    private fun sendEvent(
        phase: String,
        message: String?,
        currentFile: String,
        processedFiles: Int,
        totalFiles: Int?,
        bytesProcessed: Long,
        skippedFiles: Int,
        status: String? = null
    ) {
        TarProgressBus.send(
            mapOf(
                "phase" to phase,
                "status" to status,
                "message" to message,
                "currentFile" to currentFile,
                "processedFiles" to processedFiles,
                "totalFiles" to totalFiles,
                "bytesProcessed" to bytesProcessed,
                "skippedFiles" to skippedFiles
            )
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows progress for foreground TAR packing and extraction."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(
        operation: String,
        currentFile: String,
        ticker: String,
        processedFiles: Int,
        totalFiles: Int?,
        cancellable: Boolean
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags()
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val percent = totalFiles?.takeIf { it > 0 }?.let { ((processedFiles * 100) / it).coerceIn(0, 100) }
        val countText = if (totalFiles != null) "$processedFiles / $totalFiles files" else "$processedFiles files"
        val contentText = listOfNotNull(
            currentFile.ifBlank { ticker.ifBlank { "Preparing..." } },
            countText,
            percent?.let { "$it%" }
        ).joinToString(" - ")

        builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(operation)
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .setOngoing(cancellable)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setSubText(percent?.let { "$it%" } ?: countText)
        }

        if (totalFiles != null && totalFiles > 0) {
            builder.setProgress(totalFiles, processedFiles.coerceAtMost(totalFiles), false)
        } else if (cancellable) {
            builder.setProgress(0, 0, true)
        }

        if (cancellable) {
            val cancelIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, TarForegroundService::class.java).setAction(ACTION_CANCEL),
                pendingIntentFlags()
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
        }

        return builder.build()
    }

    private fun updateNotification(operation: String, currentFile: String, processedFiles: Int, totalFiles: Int?) {
        val notification = buildNotification(operation, currentFile, "", processedFiles, totalFiles, true)
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    companion object {
        const val ACTION_PACK = "com.easywhatsappbackup.easy_whatsapp_backup.action.PACK"
        const val ACTION_EXTRACT = "com.easywhatsappbackup.easy_whatsapp_backup.action.EXTRACT"
        const val ACTION_CANCEL = "com.easywhatsappbackup.easy_whatsapp_backup.action.CANCEL"

        const val EXTRA_SOURCE_URI = "sourceUri"
        const val EXTRA_OUTPUT_URI = "outputUri"
        const val EXTRA_TAR_URI = "tarUri"
        const val EXTRA_DESTINATION_URI = "destinationUri"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var cancelRequested: Boolean = false

        fun startPack(context: Context, sourceUri: String, outputUri: String) {
            val intent = Intent(context, TarForegroundService::class.java).apply {
                action = ACTION_PACK
                putExtra(EXTRA_SOURCE_URI, sourceUri)
                putExtra(EXTRA_OUTPUT_URI, outputUri)
            }
            context.startForegroundServiceCompat(intent)
        }

        fun startExtract(context: Context, tarUri: String, destinationUri: String) {
            val intent = Intent(context, TarForegroundService::class.java).apply {
                action = ACTION_EXTRACT
                putExtra(EXTRA_TAR_URI, tarUri)
                putExtra(EXTRA_DESTINATION_URI, destinationUri)
            }
            context.startForegroundServiceCompat(intent)
        }

        fun cancel(context: Context) {
            if (!isRunning) {
                TarProgressBus.send(
                    mapOf(
                        "phase" to "idle",
                        "status" to "Idle",
                        "message" to "No TAR task is running.",
                        "currentFile" to "",
                        "processedFiles" to 0,
                        "totalFiles" to null,
                        "bytesProcessed" to 0L,
                        "skippedFiles" to 0
                    )
                )
                return
            }
            val intent = Intent(context, TarForegroundService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}

private fun Context.startForegroundServiceCompat(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}
