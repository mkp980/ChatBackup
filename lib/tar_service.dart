import 'dart:async';

import 'package:flutter/services.dart';

import 'tar_progress.dart';

class TarService {
  static const MethodChannel _methods = MethodChannel('fast_tar_android');
  static const EventChannel _progressEvents =
      EventChannel('fast_tar_android/progress');

  Stream<TarProgress> get progress {
    return _progressEvents.receiveBroadcastStream().map((event) {
      return TarProgress.fromMap(event as Map<dynamic, dynamic>);
    });
  }

  Future<String?> pickSourceFolder() {
    return _methods.invokeMethod<String>('pickSourceFolder');
  }

  Future<SavedSelections> getSavedSelections() async {
    final result = await _methods.invokeMapMethod<String, dynamic>(
      'getSavedSelections',
    );
    return SavedSelections.fromMap(result ?? const {});
  }

  Future<FolderStats> getFolderStats(String uri) async {
    final result = await _methods.invokeMapMethod<String, dynamic>(
      'getFolderStats',
      {'uri': uri},
    );
    return FolderStats.fromMap(result ?? const {});
  }

  Future<AvailableSpace> getAvailableBytes(String uri) async {
    final result = await _methods.invokeMapMethod<String, dynamic>(
      'getAvailableBytes',
      {'uri': uri},
    );
    return AvailableSpace.fromMap(result ?? const {});
  }

  Future<String?> pickOutputTarFile() {
    return _methods.invokeMethod<String>('pickOutputTarFile');
  }

  Future<String?> pickTarFile() {
    return _methods.invokeMethod<String>('pickTarFile');
  }

  Future<String?> pickExtractFolder() {
    return _methods.invokeMethod<String>('pickExtractFolder');
  }

  Future<TarResult> packToTar({String? sourceUri, String? outputUri}) async {
    final result = await _methods.invokeMapMethod<String, dynamic>(
      'packToTar',
      {
        if (sourceUri != null) 'sourceUri': sourceUri,
        if (outputUri != null) 'outputUri': outputUri,
      },
    );
    return TarResult.fromMap(result ?? const {});
  }

  Future<TarResult> extractTar({String? tarUri, String? extractUri}) async {
    final result = await _methods.invokeMapMethod<String, dynamic>(
      'extractTar',
      {
        if (tarUri != null) 'tarUri': tarUri,
        if (extractUri != null) 'extractUri': extractUri,
      },
    );
    return TarResult.fromMap(result ?? const {});
  }

  Future<String> cancelCurrentTask() async {
    final result =
        await _methods.invokeMapMethod<String, dynamic>('cancelCurrentTask');
    return (result?['status'] as String?) ?? 'Cancelling';
  }

  Future<void> sendFeedback() {
    return _methods.invokeMethod<void>('sendFeedback');
  }
}

class FolderStats {
  const FolderStats({
    required this.bytes,
    required this.files,
    required this.skipped,
    required this.cached,
  });

  factory FolderStats.fromMap(Map<dynamic, dynamic> map) {
    return FolderStats(
      bytes: (map['bytes'] as num?)?.toInt() ?? 0,
      files: (map['files'] as num?)?.toInt() ?? 0,
      skipped: (map['skipped'] as num?)?.toInt() ?? 0,
      cached: (map['cached'] as bool?) ?? false,
    );
  }

  final int bytes;
  final int files;
  final int skipped;
  final bool cached;
}

class AvailableSpace {
  const AvailableSpace({
    required this.canCheck,
    required this.availableBytes,
  });

  factory AvailableSpace.fromMap(Map<dynamic, dynamic> map) {
    return AvailableSpace(
      canCheck: (map['canCheck'] as bool?) ?? false,
      availableBytes: (map['availableBytes'] as num?)?.toInt(),
    );
  }

  final bool canCheck;
  final int? availableBytes;
}

class SavedSelections {
  const SavedSelections({
    required this.sourceFolderUri,
    required this.outputTarUri,
    required this.inputTarUri,
    required this.extractFolderUri,
  });

  factory SavedSelections.fromMap(Map<dynamic, dynamic> map) {
    return SavedSelections(
      sourceFolderUri: map['sourceFolderUri'] as String?,
      outputTarUri: map['outputTarUri'] as String?,
      inputTarUri: map['inputTarUri'] as String?,
      extractFolderUri: map['extractFolderUri'] as String?,
    );
  }

  final String? sourceFolderUri;
  final String? outputTarUri;
  final String? inputTarUri;
  final String? extractFolderUri;
}

class TarResult {
  const TarResult({
    required this.status,
    required this.processedFiles,
    required this.totalFiles,
    required this.bytesProcessed,
    required this.skippedFiles,
    required this.skippedNames,
  });

  factory TarResult.fromMap(Map<dynamic, dynamic> map) {
    return TarResult(
      status: (map['status'] as String?) ?? 'Started',
      processedFiles: (map['processedFiles'] as num?)?.toInt() ?? 0,
      totalFiles: (map['totalFiles'] as num?)?.toInt() ?? 0,
      bytesProcessed: (map['bytesProcessed'] as num?)?.toInt() ?? 0,
      skippedFiles: (map['skippedFiles'] as num?)?.toInt() ?? 0,
      skippedNames: ((map['skippedNames'] as List<dynamic>?) ?? const [])
          .map((name) => name.toString())
          .toList(growable: false),
    );
  }

  final String status;
  final int processedFiles;
  final int totalFiles;
  final int bytesProcessed;
  final int skippedFiles;
  final List<String> skippedNames;
}
