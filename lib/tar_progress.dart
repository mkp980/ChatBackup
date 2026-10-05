class TarProgress {
  const TarProgress({
    required this.phase,
    required this.status,
    required this.message,
    required this.currentFile,
    required this.processedFiles,
    required this.totalFiles,
    required this.bytesProcessed,
    required this.skippedFiles,
  });

  factory TarProgress.fromMap(Map<dynamic, dynamic> map) {
    return TarProgress(
      phase: (map['phase'] as String?) ?? 'idle',
      status: map['status'] as String?,
      message: map['message'] as String?,
      currentFile: (map['currentFile'] as String?) ?? '',
      processedFiles: (map['processedFiles'] as num?)?.toInt() ?? 0,
      totalFiles: (map['totalFiles'] as num?)?.toInt(),
      bytesProcessed: (map['bytesProcessed'] as num?)?.toInt() ?? 0,
      skippedFiles: (map['skippedFiles'] as num?)?.toInt() ?? 0,
    );
  }

  final String phase;
  final String? status;
  final String? message;
  final String currentFile;
  final int processedFiles;
  final int? totalFiles;
  final int bytesProcessed;
  final int skippedFiles;

  bool get isActive {
    return phase == 'starting' ||
        phase == 'packing' ||
        phase == 'extracting' ||
        phase == 'cancelling';
  }

  bool get isFinal {
    final value = status?.toLowerCase();
    return value == 'completed' || value == 'failed' || value == 'cancelled';
  }

  String get filesLabel {
    if (totalFiles == null) return '$processedFiles files';
    return '$processedFiles / $totalFiles files';
  }

  String get bytesLabel {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    double value = bytesProcessed.toDouble();
    var unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    if (unit == 0) return '${value.toInt()} ${units[unit]}';
    return '${value.toStringAsFixed(1)} ${units[unit]}';
  }
}
