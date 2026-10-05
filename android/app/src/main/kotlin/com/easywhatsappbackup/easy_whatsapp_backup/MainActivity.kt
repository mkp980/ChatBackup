package com.easywhatsappbackup.easy_whatsapp_backup

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val methodChannelName = "fast_tar_android"
    private val progressChannelName = "fast_tar_android/progress"

    private val requestPickSourceFolder = 1001
    private val requestPickOutputTar = 1002
    private val requestPickTarFile = 1003
    private val requestPickExtractFolder = 1004
    private val requestPostNotifications = 1005

    private lateinit var safHelper: SafHelper
    private var pendingResult: MethodChannel.Result? = null
    private var pendingRequestCode: Int? = null
    private var progressSink: EventChannel.EventSink? = null
    private val progressListener: (Map<String, Any?>) -> Unit = { event ->
        runOnUiThread { progressSink?.success(event) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        safHelper = SafHelper(this)
        requestNotificationPermissionIfNeeded()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, methodChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "pickSourceFolder" -> launchFolderPicker(requestPickSourceFolder, result)
                    "pickOutputTarFile" -> launchCreateTarPicker(result)
                    "pickTarFile" -> launchTarFilePicker(result)
                    "pickExtractFolder" -> launchFolderPicker(requestPickExtractFolder, result)
                    "packToTar" -> runPack(call, result)
                    "extractTar" -> runExtract(call, result)
                    "cancelCurrentTask" -> cancelCurrentTask(result)
                    "getSavedSelections" -> getSavedSelections(result)
                    "sendFeedback" -> sendFeedback(result)
                    "getFolderStats" -> getFolderStats(call, result)
                    "getAvailableBytes" -> getAvailableBytes(call, result)
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, progressChannelName)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    progressSink = events
                    val lastEvent = TarProgressBus.addListener(progressListener)
                    if (lastEvent != null) events?.success(lastEvent)
                }

                override fun onCancel(arguments: Any?) {
                    TarProgressBus.removeListener(progressListener)
                    progressSink = null
                }
            })
    }

    private fun launchFolderPicker(requestCode: Int, result: MethodChannel.Result) {
        if (!setPendingResult(requestCode, result)) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (requestCode == requestPickSourceFolder || requestCode == requestPickExtractFolder) {
                // SAF still requires the user to approve access; this only nudges
                // the Android picker to the common Android/media app-folder area.
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, defaultMediaTreeUri())
            }
        }
        startActivityForResult(intent, requestCode)
    }

    private fun launchCreateTarPicker(result: MethodChannel.Result) {
        if (!setPendingResult(requestPickOutputTar, result)) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-tar"
            putExtra(Intent.EXTRA_TITLE, "chat-app-backup.tar")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestPickOutputTar)
    }

    private fun launchTarFilePicker(result: MethodChannel.Result) {
        if (!setPendingResult(requestPickTarFile, result)) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-tar", "application/octet-stream"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestPickTarFile)
    }

    private fun setPendingResult(requestCode: Int, result: MethodChannel.Result): Boolean {
        if (pendingResult != null) {
            result.error("PICKER_BUSY", "Another Android picker is already open.", null)
            return false
        }
        pendingRequestCode = requestCode
        pendingResult = result
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val result = pendingResult ?: return
        if (pendingRequestCode != requestCode) return

        pendingResult = null
        pendingRequestCode = null

        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            result.success(null)
            return
        }

        val uri = data.data!!
        safHelper.persistUriPermission(uri, data.flags, requestCode == requestPickOutputTar)

        when (requestCode) {
            requestPickSourceFolder -> safHelper.sourceFolderUri = uri.toString()
            requestPickOutputTar -> safHelper.outputTarUri = uri.toString()
            requestPickTarFile -> safHelper.inputTarUri = uri.toString()
            requestPickExtractFolder -> safHelper.extractFolderUri = uri.toString()
        }
        result.success(uri.toString())
    }

    private fun runPack(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *> ?: emptyMap<String, String>()
        val sourceUri = args["sourceUri"] as? String ?: safHelper.sourceFolderUri
        val outputUri = args["outputUri"] as? String ?: safHelper.outputTarUri
        if (sourceUri.isNullOrBlank()) {
            result.error("MISSING_SOURCE", "Pick a source folder first.", null)
            return
        }
        if (outputUri.isNullOrBlank()) {
            result.error("MISSING_OUTPUT", "Pick an output TAR file first.", null)
            return
        }

        if (TarForegroundService.isRunning) {
            result.error("TASK_RUNNING", "Another TAR task is already running.", null)
            return
        }

        TarForegroundService.startPack(this, sourceUri, outputUri)
        result.success(startedResult("Packing"))
    }

    private fun runExtract(call: MethodCall, result: MethodChannel.Result) {
        val args = call.arguments as? Map<*, *> ?: emptyMap<String, String>()
        val tarUri = args["tarUri"] as? String ?: safHelper.inputTarUri
        val extractUri = args["extractUri"] as? String ?: safHelper.extractFolderUri
        if (tarUri.isNullOrBlank()) {
            result.error("MISSING_TAR", "Pick a TAR file first.", null)
            return
        }
        if (extractUri.isNullOrBlank()) {
            result.error("MISSING_EXTRACT_FOLDER", "Pick an extract folder first.", null)
            return
        }

        if (TarForegroundService.isRunning) {
            result.error("TASK_RUNNING", "Another TAR task is already running.", null)
            return
        }

        TarForegroundService.startExtract(this, tarUri, extractUri)
        result.success(startedResult("Extracting"))
    }

    private fun cancelCurrentTask(result: MethodChannel.Result) {
        if (!TarForegroundService.isRunning) {
            result.success(mapOf("status" to "Idle"))
            return
        }
        TarForegroundService.cancel(this)
        result.success(mapOf("status" to "Cancelling"))
    }

    private fun getSavedSelections(result: MethodChannel.Result) {
        result.success(
            mapOf(
                "sourceFolderUri" to safHelper.sourceFolderUri,
                "outputTarUri" to safHelper.outputTarUri,
                "inputTarUri" to safHelper.inputTarUri,
                "extractFolderUri" to safHelper.extractFolderUri
            )
        )
    }

    private fun sendFeedback(result: MethodChannel.Result) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf("mkp980@gmail.com"))
            putExtra(Intent.EXTRA_SUBJECT, "Easy Chat App Mover Feedback")
        }
        val chooser = Intent.createChooser(intent, "Send feedback")
        try {
            startActivity(chooser)
            result.success(true)
        } catch (_: Exception) {
            result.error("NO_EMAIL_APP", "No email app is available for feedback.", null)
        }
    }

    private fun getFolderStats(call: MethodCall, result: MethodChannel.Result) {
        val uriText = call.argument<String>("uri")
        if (uriText.isNullOrBlank()) {
            result.error("MISSING_URI", "Missing folder URI.", null)
            return
        }

        val cachedStats = safHelper.cachedFolderStats(uriText)
        if (cachedStats != null) {
            result.success(folderStatsMap(cachedStats, cached = true))
            return
        }

        Thread {
            try {
                val stats = FolderStatsCalculator(this).calculateFolderStats(Uri.parse(uriText))
                safHelper.cacheFolderStats(uriText, stats)
                runOnUiThread {
                    result.success(folderStatsMap(stats, cached = false))
                }
            } catch (error: Exception) {
                runOnUiThread {
                    result.error("FOLDER_STATS_FAILED", error.message ?: "Could not calculate folder size.", null)
                }
            }
        }.start()
    }

    private fun folderStatsMap(stats: FolderStats, cached: Boolean): Map<String, Any> {
        return mapOf(
            "bytes" to stats.bytes,
            "files" to stats.files,
            "skipped" to stats.skipped,
            "cached" to cached
        )
    }

    private fun getAvailableBytes(call: MethodCall, result: MethodChannel.Result) {
        val uriText = call.argument<String>("uri")
        if (uriText.isNullOrBlank()) {
            result.error("MISSING_URI", "Missing destination URI.", null)
            return
        }

        Thread {
            val availableBytes = FolderStatsCalculator(this).availableBytesForUri(Uri.parse(uriText))
            runOnUiThread {
                result.success(
                    mapOf(
                        "canCheck" to (availableBytes != null),
                        "availableBytes" to availableBytes
                    )
                )
            }
        }.start()
    }

    private fun defaultMediaTreeUri(): Uri {
        return DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Android/media"
        )
    }

    private fun startedResult(operation: String): Map<String, Any> {
        return mapOf(
            "status" to "Started",
            "operation" to operation,
            "processedFiles" to 0,
            "totalFiles" to 0,
            "bytesProcessed" to 0L,
            "skippedFiles" to 0,
            "skippedNames" to emptyList<String>()
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestPostNotifications)
    }
}
