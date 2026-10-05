import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'tar_progress.dart';
import 'tar_service.dart';

void main() {
  runApp(const EasyWhatsappBackupApp());
}

class EasyWhatsappBackupApp extends StatelessWidget {
  const EasyWhatsappBackupApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Easy Chat App Mover',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0F8F72)),
        useMaterial3: true,
      ),
      home: const BackupHomePage(),
    );
  }
}

class BackupHomePage extends StatefulWidget {
  const BackupHomePage({super.key});

  @override
  State<BackupHomePage> createState() => _BackupHomePageState();
}

class _BackupHomePageState extends State<BackupHomePage> {
  static const String _folderPermissionHint =
      'Select any folder to back up. For chat application folders (like WhatsApp) inside Android/media, approve the specific folder in Android\'s picker.';

  final TarService _service = TarService();
  StreamSubscription<TarProgress>? _progressSubscription;

  String? _sourceFolder;
  String? _sourceFolderLabel;
  String? _outputTar;
  String? _inputTar;
  String? _extractFolder;
  String? _extractFolderLabel;
  FolderStats? _sourceFolderStats;
  AvailableSpace? _backupLocationSpace;
  bool _calculatingSourceSize = false;
  TarProgress? _progress;
  bool _busy = false;
  String? _lastFinalKey;

  @override
  void initState() {
    super.initState();
    _progressSubscription = _service.progress.listen((progress) {
      if (!mounted) return;
      setState(() {
        _progress = progress;
        _busy = progress.isActive;
      });
      if (progress.isFinal) {
        final key =
            '${progress.status}-${progress.processedFiles}-${progress.skippedFiles}-${progress.message}';
        if (_lastFinalKey != key) {
          _lastFinalKey = key;
          final skipped = progress.skippedFiles == 0
              ? ''
              : ' Skipped ${progress.skippedFiles} file(s).';
          _showMessage(
            '${progress.status ?? 'Finished'}. ${progress.processedFiles} file(s) processed.$skipped',
            isError: progress.status == 'Failed',
          );
        }
        if (mounted) setState(() => _busy = false);
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _loadSavedSelections();
    });
  }

  @override
  void dispose() {
    _progressSubscription?.cancel();
    super.dispose();
  }

  Future<void> _pick(
    Future<String?> Function() picker,
    ValueChanged<String> save,
  ) async {
    try {
      final uri = await picker();
      if (uri == null) return;
      setState(() => save(uri));
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } catch (error) {
      _showError(error.toString());
    }
  }

  Future<void> _pickSourceFolder() async {
    await _pick(_service.pickSourceFolder, (value) {
      _sourceFolder = value;
      _sourceFolderLabel = null;
      _sourceFolderStats = null;
    });
    if (_sourceFolder != null) {
      _calculateSourceFolderStats(_sourceFolder!);
    }
  }

  Future<void> _pickRestoreFolder() async {
    await _pick(_service.pickExtractFolder, (value) {
      _extractFolder = value;
      _extractFolderLabel = null;
    });
  }

  Future<void> _loadSavedSelections() async {
    try {
      final selections = await _service.getSavedSelections();
      if (!mounted) return;
      setState(() {
        _sourceFolder = selections.sourceFolderUri;
        _outputTar = selections.outputTarUri;
        _inputTar = selections.inputTarUri;
        _extractFolder = selections.extractFolderUri;
      });
      if (selections.sourceFolderUri != null) {
        _calculateSourceFolderStats(selections.sourceFolderUri!);
      }
      if (selections.outputTarUri != null) {
        _checkBackupLocationSpace(selections.outputTarUri!);
      }
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } catch (error) {
      _showError(error.toString());
    }
  }

  Future<void> _run(Future<TarResult> Function() operation, String verb) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _lastFinalKey = null;
    });
    try {
      final result = await operation();
      if (!mounted) return;
      final startedLabel = result.status == 'Started' ? verb : result.status;
      _showMessage('$startedLabel started. You can switch apps while it runs.');
    } on PlatformException catch (error) {
      if (mounted) setState(() => _busy = false);
      _showError(error.message ?? error.code);
    } catch (error) {
      if (mounted) setState(() => _busy = false);
      _showError(error.toString());
    }
  }

  Future<void> _createBackup() async {
    if (_sourceFolder == null) {
      await _pickSourceFolder();
      if (_sourceFolder == null) return;
    }
    if (!await _ensureBackupLocation()) return;
    final stats = _sourceFolderStats;
    final space = _backupLocationSpace;
    if (stats != null &&
        space?.canCheck == true &&
        space?.availableBytes != null &&
        space!.availableBytes! <= stats.bytes) {
      _showError(
        'Not enough free space. Need ${_formatBytes(stats.bytes)}, available ${_formatBytes(space.availableBytes!)}.',
      );
      return;
    }
    await _run(
      () => _service.packToTar(sourceUri: _sourceFolder, outputUri: _outputTar),
      'Packing',
    );
  }

  Future<void> _restoreBackup() async {
    if (_extractFolder == null) {
      await _pickRestoreFolder();
      if (_extractFolder == null) return;
    }
    if (!await _ensureBackupFile()) return;
    await _run(
      () => _service.extractTar(tarUri: _inputTar, extractUri: _extractFolder),
      'Extraction',
    );
  }

  Future<bool> _ensureBackupLocation() async {
    if (_outputTar != null) return true;
    try {
      final uri = await _service.pickOutputTarFile();
      if (uri == null) return false;
      if (!mounted) return false;
      setState(() => _outputTar = uri);
      _checkBackupLocationSpace(uri);
      return true;
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } catch (error) {
      _showError(error.toString());
    }
    return false;
  }

  Future<void> _pickBackupLocation() async {
    try {
      final uri = await _service.pickOutputTarFile();
      if (uri == null) return;
      if (!mounted) return;
      setState(() {
        _outputTar = uri;
        _backupLocationSpace = null;
      });
      _checkBackupLocationSpace(uri);
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } catch (error) {
      _showError(error.toString());
    }
  }

  Future<void> _calculateSourceFolderStats(String uri) async {
    final token = uri;
    setState(() {
      _calculatingSourceSize = true;
      _sourceFolderStats = null;
    });
    try {
      final stats = await _service.getFolderStats(uri);
      if (!mounted || _sourceFolder != token) return;
      setState(() {
        _sourceFolderStats = stats;
        _calculatingSourceSize = false;
      });
      if (_outputTar != null) {
        _checkBackupLocationSpace(_outputTar!);
      }
    } on PlatformException catch (error) {
      if (mounted && _sourceFolder == token) {
        setState(() => _calculatingSourceSize = false);
      }
      _showError(error.message ?? error.code);
    } catch (error) {
      if (mounted && _sourceFolder == token) {
        setState(() => _calculatingSourceSize = false);
      }
      _showError(error.toString());
    }
  }

  Future<void> _checkBackupLocationSpace(String uri) async {
    try {
      final space = await _service.getAvailableBytes(uri);
      if (!mounted || _outputTar != uri) return;
      setState(() => _backupLocationSpace = space);
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } catch (error) {
      _showError(error.toString());
    }
  }

  Future<bool> _ensureBackupFile() async {
    if (_inputTar != null) return true;
    try {
      final uri = await _service.pickTarFile();
      if (uri == null) return false;
      if (!mounted) return false;
      setState(() => _inputTar = uri);
      return true;
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } catch (error) {
      _showError(error.toString());
    }
    return false;
  }

  Future<void> _cancelCurrentTask() async {
    try {
      final status = await _service.cancelCurrentTask();
      _showMessage(status);
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } catch (error) {
      _showError(error.toString());
    }
  }

  Future<void> _sendFeedback() async {
    try {
      await _service.sendFeedback();
    } on PlatformException catch (error) {
      _showError(error.message ?? error.code);
    } catch (error) {
      _showError(error.toString());
    }
  }

  void _showGuide() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (context) => const _GuideSheet(),
    );
  }

  void _showError(String message) {
    _showMessage(message, isError: true);
  }

  void _showMessage(String message, {bool isError = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: isError ? Theme.of(context).colorScheme.error : null,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final canPack = !_busy;
    final canExtract = !_busy;
    final sourceStats = _sourceFolderStats;
    final backupSpace = _backupLocationSpace;
    final notEnoughSpace = sourceStats != null &&
        backupSpace?.canCheck == true &&
        backupSpace?.availableBytes != null &&
        backupSpace!.availableBytes! <= sourceStats.bytes;

    return Scaffold(
      appBar: AppBar(title: const Text('Easy Chat App Mover')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Center(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Image.asset(
                  'assets/logo.png',
                  height: 128,
                  fit: BoxFit.contain,
                ),
              ),
            ),
            const SizedBox(height: 20),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _showGuide,
                    icon: const Icon(Icons.help_outline),
                    label: const Text('Guide'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _sendFeedback,
                    icon: const Icon(Icons.feedback_outlined),
                    label: const Text('Feedback'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            _Section(
              title: 'Create Backup',
              children: [
                _ActionButton(
                  icon: Icons.folder_open,
                  label: 'Pick Folder to Back Up',
                  onPressed: _busy ? null : _pickSourceFolder,
                ),
                _SelectionLabel(
                  label: 'Source Folder',
                  value: _sourceFolderLabel ??
                      _sourceFolder ??
                      _folderPermissionHint,
                ),
                _FolderSizeStatus(
                  calculating: _calculatingSourceSize,
                  stats: sourceStats,
                ),
                const SizedBox(height: 12),
                _ActionButton(
                  icon: Icons.note_add,
                  label: 'Pick Backup File Location',
                  onPressed: _busy ? null : _pickBackupLocation,
                ),
                _SelectionLabel(label: 'Backup Location', value: _outputTar),
                _BackupSpaceStatus(
                  sourceStats: sourceStats,
                  availableSpace: backupSpace,
                  notEnoughSpace: notEnoughSpace,
                ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  onPressed: canPack ? _createBackup : null,
                  icon: const Icon(Icons.archive),
                  label: const Text('Create Backup'),
                ),
              ],
            ),
            const SizedBox(height: 20),
            _Section(
              title: 'Restore Backup',
              children: [
                _ActionButton(
                  icon: Icons.insert_drive_file,
                  label: 'Pick Backup File',
                  onPressed: _busy
                      ? null
                      : () => _pick(
                            _service.pickTarFile,
                            (value) => _inputTar = value,
                          ),
                ),
                _SelectionLabel(label: 'Backup File', value: _inputTar),
                const SizedBox(height: 12),
                _ActionButton(
                  icon: Icons.create_new_folder,
                  label: 'Pick Restore Folder',
                  onPressed: _busy ? null : _pickRestoreFolder,
                ),
                _SelectionLabel(
                  label: 'Restore Location',
                  value: _extractFolderLabel ??
                      _extractFolder ??
                      _folderPermissionHint,
                ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  onPressed: canExtract ? _restoreBackup : null,
                  icon: const Icon(Icons.unarchive),
                  label: const Text('Restore Backup'),
                ),
              ],
            ),
            const SizedBox(height: 20),
            OutlinedButton.icon(
              onPressed: _busy ? _cancelCurrentTask : null,
              icon: const Icon(Icons.cancel),
              label: const Text('Cancel Current Task'),
            ),
            const SizedBox(height: 20),
            _ProgressPanel(progress: _progress, busy: _busy),
            const SizedBox(height: 24),
            Center(
              child: Text(
                'Built by KPx',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GuideSheet extends StatefulWidget {
  const _GuideSheet();

  @override
  State<_GuideSheet> createState() => _GuideSheetState();
}

class _GuideSheetState extends State<_GuideSheet> {
  final PageController _controller = PageController();
  int _page = 0;

  static const List<_GuidePageData> _pages = [
    _GuidePageData(
      title: 'Before You Start',
      body:
          'This app packs and restores selected folders into one backup file. For chat applications (like WhatsApp), create a local chat backup inside the chat app first when supported.\n\n'
          '1. Open your chat application.\n'
          '2. Go to its chat backup settings.\n'
          '3. Create a local backup and wait until it is complete.',
    ),
    _GuidePageData(
      title: 'Create a Backup File',
      body:
          '1. Open Easy Chat App Mover.\n'
          '2. Tap Pick Folder to Back Up.\n'
          '3. Select the folder you want to back up. For Android/media app folders, approve the folder through Android\'s picker.\n'
          '4. Tap Pick Backup File Location.\n'
          '5. Choose where to save the backup file.\n'
          '6. Tap Create Backup and wait until it finishes.\n\n'
          'The app creates one backup file, which is easier to copy or share than thousands of individual files.',
    ),
    _GuidePageData(
      title: 'Move to Your New Phone',
      body:
          'Copy the backup file to your new phone using any method you prefer:\n\n'
          '- USB cable\n'
          '- SD card\n'
          '- External drive\n'
          '- Nearby Share or Quick Share\n'
          '- Cloud storage such as Google Drive or OneDrive\n\n'
          'Then install Easy Chat App Mover on the new phone.',
    ),
    _GuidePageData(
      title: 'Restore the Backup',
      body:
          '1. Open Easy Chat App Mover.\n'
          '2. Tap Pick Backup File and choose the backup file you copied.\n'
          '3. Tap Pick Restore Folder.\n'
          '4. Select the folder where files should be restored.\n'
          '5. Tap Restore Backup.\n'
          '6. Wait until the restore process is complete.',
    ),
    _GuidePageData(
      title: 'Restore Chats',
      body:
          '1. Install or reinstall your chat application.\n'
          '2. Open the chat application and verify your account.\n'
          '3. If that app supports local restore, it should detect the restored backup.\n'
          '4. Tap Restore when prompted.\n'
          '5. Wait for restoration to finish.',
    ),
    _GuidePageData(
      title: 'Important Notes',
      body:
          '- If your chat application supports local backups, create one before creating a backup with this app.\n'
          '- Do not rename or modify the backup file.\n'
          '- Make sure both devices have enough free storage.\n'
          '- Keep the app open during backup or restore for the fastest and most reliable operation.\n'
          '- If your chat application does not detect the backup, restore files to the correct app folder before opening it.',
    ),
  ];

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: MediaQuery.sizeOf(context).height * 0.82,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 8, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    'User Guide',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
                IconButton(
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.close),
                  tooltip: 'Close',
                ),
              ],
            ),
          ),
          Expanded(
            child: PageView.builder(
              controller: _controller,
              itemCount: _pages.length,
              onPageChanged: (value) => setState(() => _page = value),
              itemBuilder: (context, index) {
                final page = _pages[index];
                return SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        page.title,
                        style: Theme.of(context).textTheme.headlineSmall,
                      ),
                      const SizedBox(height: 16),
                      Text(
                        page.body,
                        style: Theme.of(context).textTheme.bodyLarge,
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 20),
            child: Row(
              children: [
                IconButton(
                  onPressed: _page == 0 ? null : _previousPage,
                  icon: const Icon(Icons.chevron_left),
                  tooltip: 'Previous',
                ),
                Expanded(
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: List.generate(_pages.length, (index) {
                      final active = index == _page;
                      return AnimatedContainer(
                        duration: const Duration(milliseconds: 180),
                        margin: const EdgeInsets.symmetric(horizontal: 4),
                        width: active ? 22 : 8,
                        height: 8,
                        decoration: BoxDecoration(
                          borderRadius: BorderRadius.circular(8),
                          color: active
                              ? Theme.of(context).colorScheme.primary
                              : Theme.of(context).colorScheme.outlineVariant,
                        ),
                      );
                    }),
                  ),
                ),
                IconButton(
                  onPressed:
                      _page == _pages.length - 1 ? null : _nextPage,
                  icon: const Icon(Icons.chevron_right),
                  tooltip: 'Next',
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _previousPage() {
    _controller.previousPage(
      duration: const Duration(milliseconds: 220),
      curve: Curves.easeOut,
    );
  }

  void _nextPage() {
    _controller.nextPage(
      duration: const Duration(milliseconds: 220),
      curve: Curves.easeOut,
    );
  }
}

class _GuidePageData {
  const _GuidePageData({
    required this.title,
    required this.body,
  });

  final String title;
  final String body;
}

class _FolderSizeStatus extends StatelessWidget {
  const _FolderSizeStatus({
    required this.calculating,
    required this.stats,
  });

  final bool calculating;
  final FolderStats? stats;

  @override
  Widget build(BuildContext context) {
    if (calculating) {
      return const Padding(
        padding: EdgeInsets.only(top: 8),
        child: Row(
          children: [
            SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
            SizedBox(width: 10),
            Text('Calculating folder size...'),
          ],
        ),
      );
    }

    final value = stats;
    if (value == null) return const SizedBox.shrink();

    final skippedText = value.skipped == 0 ? '' : ' â€¢ ${value.skipped} skipped';
    final cachedText = value.cached ? ' â€¢ cached' : '';
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Text(
        'Size: ${_formatBytes(value.bytes)} â€¢ ${value.files} files$skippedText$cachedText',
        style: Theme.of(context).textTheme.bodySmall,
      ),
    );
  }
}

class _BackupSpaceStatus extends StatelessWidget {
  const _BackupSpaceStatus({
    required this.sourceStats,
    required this.availableSpace,
    required this.notEnoughSpace,
  });

  final FolderStats? sourceStats;
  final AvailableSpace? availableSpace;
  final bool notEnoughSpace;

  @override
  Widget build(BuildContext context) {
    final source = sourceStats;
    final space = availableSpace;
    if (source == null || space == null) return const SizedBox.shrink();

    if (!space.canCheck || space.availableBytes == null) {
      return Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text(
          'Free space could not be verified for this location.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      );
    }

    if (!notEnoughSpace) {
      return Padding(
        padding: const EdgeInsets.only(top: 8),
        child: Text(
          'Available: ${_formatBytes(space.availableBytes!)}',
          style: Theme.of(context).textTheme.bodySmall,
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Text(
        'Not enough free space. Need ${_formatBytes(source.bytes)}, available ${_formatBytes(space.availableBytes!)}.',
        style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.error,
            ),
      ),
    );
  }
}

String _formatBytes(int bytes) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  var value = bytes.toDouble();
  var unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  if (unit == 0) return '${value.toInt()} ${units[unit]}';
  return '${value.toStringAsFixed(1)} ${units[unit]}';
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 12),
        ...children,
      ],
    );
  }
}

class _ActionButton extends StatelessWidget {
  const _ActionButton({
    required this.icon,
    required this.label,
    required this.onPressed,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon),
      label: Text(label),
    );
  }
}

class _SelectionLabel extends StatelessWidget {
  const _SelectionLabel({required this.label, required this.value});

  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: textTheme.labelLarge),
          Text(
            value ?? 'Nothing selected',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}

class _ProgressPanel extends StatelessWidget {
  const _ProgressPanel({required this.progress, required this.busy});

  final TarProgress? progress;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final value = progress;
    if (!busy && !(value?.isActive ?? false)) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Progress', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 12),
          Text(value?.status ?? 'No active task'),
          if (value?.message?.isNotEmpty ?? false) Text(value!.message!),
          if (value?.status != null)
            Text(
              'Last result: ${value!.processedFiles} file(s), ${value.skippedFiles} skipped',
            ),
        ],
      );
    }

    final current =
        value?.currentFile.isEmpty ?? true ? 'Waiting' : value!.currentFile;
    final phase = value?.phase ?? 'idle';
    final status = value?.status;
    final total = value?.totalFiles;
    final processed = value?.processedFiles ?? 0;
    final progressValue =
        busy && total != null && total > 0 ? processed / total : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Progress', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 12),
        LinearProgressIndicator(value: progressValue),
        const SizedBox(height: 12),
        Text('Status: ${status ?? phase}'),
        if (value?.message?.isNotEmpty ?? false) Text(value!.message!),
        Text(
          'Current file: $current',
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        Text('Processed: ${value?.filesLabel ?? '0 files'}'),
        Text('Bytes: ${value?.bytesLabel ?? '0 B'}'),
        Text('Skipped: ${value?.skippedFiles ?? 0}'),
      ],
    );
  }
}

