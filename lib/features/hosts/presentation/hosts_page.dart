import 'dart:async';

import 'package:conduit/core/presentation/conduit_brand.dart';
import 'package:conduit/core/presentation/system_navigation_insets.dart';
import 'package:conduit/core/presentation/theme_sheet.dart';
import 'package:conduit/core/theme/theme_controller.dart';
import 'package:conduit/features/adb/data/nexus_adb_client.dart';
import 'package:conduit/features/app_lock/presentation/app_lock_controller.dart';
import 'package:conduit/features/backup/data/app_backup_service.dart';
import 'package:conduit/features/hosts/domain/saved_host.dart';
import 'package:conduit/features/hosts/domain/saved_hosts_repository.dart';
import 'package:conduit/features/hosts/presentation/host_form_page.dart';
import 'package:conduit/features/hosts/presentation/hosts_controller.dart';
import 'package:conduit/features/hosts/presentation/widgets/connection_fab.dart';
import 'package:conduit/features/hosts/presentation/widgets/host_card.dart';
import 'package:conduit/features/hosts/presentation/widgets/host_search_field.dart';
import 'package:conduit/features/hosts/presentation/widgets/hosts_hero.dart';
import 'package:conduit/features/hosts/presentation/widgets/message_state.dart';
import 'package:conduit/features/hosts/presentation/widgets/tag_filter_bar.dart';
import 'package:conduit/features/local_shell/domain/local_shell_instance.dart';
import 'package:conduit/features/local_shell/presentation/local_shell_controller.dart';
import 'package:conduit/features/local_shell/presentation/local_shell_instance_page.dart';
import 'package:conduit/features/local_shell/presentation/local_shell_setup_page.dart';
import 'package:conduit/features/local_shell/presentation/widgets/local_shell_section.dart';
import 'package:conduit/features/sftp/domain/file_export.dart';
import 'package:conduit/features/sftp/domain/sftp_repository.dart';
import 'package:conduit/features/sftp/presentation/sftp_browser_page.dart';
import 'package:conduit/features/terminal/domain/host_key_prompt.dart';
import 'package:conduit/features/terminal/domain/host_key_verifier.dart';
import 'package:conduit/features/terminal/domain/ssh_terminal_repository.dart';
import 'package:conduit/features/terminal/presentation/host_key_prompt_coordinator.dart';
import 'package:conduit/features/terminal/presentation/host_key_prompt_dialog.dart';
import 'package:conduit/features/terminal/presentation/terminal_page.dart';
import 'package:conduit/features/terminal/presentation/terminal_workspace_controller.dart';
import 'package:conduit/features/terminal/presentation/trusted_keys_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:uuid/uuid.dart';

class HostsPage extends StatefulWidget {
  const HostsPage({
    required this.hostsController,
    required this.lockController,
    required this.terminalRepository,
    required this.workspaceController,
    required this.localShellController,
    required this.themeController,
    required this.hostKeyVerifier,
    required this.promptCoordinator,
    required this.sftpRepository,
    required this.backupService,
    required this.fileExport,
    this.adbClient = const NexusAdbClient(),
    super.key,
  });

  final HostsController hostsController;
  final AppLockController lockController;
  final SshTerminalRepository terminalRepository;
  final TerminalWorkspaceController workspaceController;
  final LocalShellController localShellController;
  final ThemeController themeController;
  final HostKeyVerifier hostKeyVerifier;
  final HostKeyPromptCoordinator promptCoordinator;
  final SftpRepository sftpRepository;
  final AppBackupService backupService;
  final FileExport fileExport;
  final NexusAdbClient adbClient;

  @override
  State<HostsPage> createState() => _HostsPageState();
}

class _HostsPageState extends State<HostsPage> {
  final _searchController = TextEditingController();
  bool _terminalPageOpen = false;
  bool _showingHostKeyPrompt = false;
  String _query = '';
  String? _selectedTag;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(widget.hostsController.load());
      unawaited(widget.localShellController.refresh());
      _handlePromptChanged();
    });
    widget.promptCoordinator.addListener(_handlePromptChanged);
  }

  @override
  void dispose() {
    widget.promptCoordinator.removeListener(_handlePromptChanged);
    widget.promptCoordinator.rejectAll();
    _searchController.dispose();
    super.dispose();
  }

  void _handlePromptChanged() {
    if (_showingHostKeyPrompt || !mounted) return;
    if (widget.promptCoordinator.current == null) return;
    _showingHostKeyPrompt = true;
    Future<void>.microtask(() async {
      try {
        while (true) {
          final next = widget.promptCoordinator.current;
          if (next == null) break;
          final decision = await _requestHostKeyDecision(next);
          widget.promptCoordinator.resolve(next, decision);
        }
      } finally {
        _showingHostKeyPrompt = false;
      }
    });
  }

  Future<HostKeyDecision> _requestHostKeyDecision(
    HostKeyPromptRequest request,
  ) async {
    if (!mounted) return HostKeyDecision.reject;
    return await showHostKeyPromptDialog(context: context, request: request) ??
        HostKeyDecision.reject;
  }

  @override
  Widget build(BuildContext context) {
    final palette = widget.themeController.palette;
    return Scaffold(
      floatingActionButton: ListenableBuilder(
        listenable: widget.hostsController,
        builder: (context, _) {
          final hideFab =
              !widget.hostsController.isLoading &&
              widget.hostsController.errorMessage == null &&
              widget.hostsController.hosts.isEmpty;
          return hideFab
              ? const SizedBox.shrink()
              : ConnectionFab(onTap: _openForm);
        },
      ),
      body: ConduitBackdrop(
        palette: palette,
        child: SafeArea(
          bottom: shouldApplyBottomSafeArea(context),
          child: Stack(
            children: [
              RefreshIndicator(
                color: Theme.of(context).colorScheme.primary,
                onRefresh: widget.hostsController.load,
                child: CustomScrollView(
                  slivers: [
                    SliverToBoxAdapter(
                      child: ListenableBuilder(
                        listenable: Listenable.merge([
                          widget.hostsController,
                          widget.workspaceController,
                        ]),
                        builder: (context, _) {
                          return HostsHero(
                            hostCount: widget.hostsController.hosts.length,
                            activeSessionCount:
                                widget.workspaceController.sessions.length,
                            onAppearance: () => showThemeSheet(
                              context: context,
                              controller: widget.themeController,
                              backupService: widget.backupService,
                            ),
                            onTrustedKeys: _openTrustedKeys,
                            onLock: _lock,
                            onOpenSessions:
                                widget.workspaceController.hasSessions
                                ? _openTerminalWorkspace
                                : null,
                          );
                        },
                      ),
                    ),
                    SliverToBoxAdapter(
                      child: ListenableBuilder(
                        listenable: Listenable.merge([
                          widget.workspaceController,
                          widget.themeController,
                        ]),
                        builder: (context, _) {
                          if (!widget.themeController.showLocalShell) {
                            return const SizedBox.shrink();
                          }
                          final activeInstanceIds = widget
                              .workspaceController
                              .sessions
                              .map(
                                (session) => localShellInstanceIdFromHostId(
                                  session.host.id,
                                ),
                              )
                              .whereType<String>()
                              .toSet();
                          return LocalShellSection(
                            controller: widget.localShellController,
                            activeInstanceIds: activeInstanceIds,
                            onAdd: _openLocalShellSetup,
                            onOpenInstance: _openLocalSession,
                            onManageInstance: _openLocalShellInstance,
                          );
                        },
                      ),
                    ),
                    ListenableBuilder(
                      listenable: Listenable.merge([
                        widget.hostsController,
                        widget.workspaceController,
                      ]),
                      builder: (context, _) => _buildBody(context),
                    ),
                  ],
                ),
              ),
              Positioned(
                left: 0,
                right: 0,
                bottom: 24,
                child: IgnorePointer(
                  child: Text(
                    'Powered by PhyLuke',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    final controller = widget.hostsController;
    if (controller.isLoading) {
      return const SliverFillRemaining(
        hasScrollBody: false,
        child: Center(child: CircularProgressIndicator()),
      );
    }

    if (controller.errorMessage != null) {
      return SliverFillRemaining(
        hasScrollBody: false,
        child: MessageState(
          icon: Icons.error_outline,
          title: 'Something went wrong',
          message: controller.errorMessage!,
          actionLabel: 'Retry',
          onAction: controller.load,
        ),
      );
    }

    if (controller.hosts.isEmpty) {
      return SliverPadding(
        padding: const EdgeInsets.fromLTRB(18, 0, 18, 120),
        sliver: SliverList(
          delegate: SliverChildListDelegate.fixed([
            const _MachineSectionHeader(),
            const SizedBox(height: 24),
            MessageState(
              icon: Icons.dns_outlined,
              title: 'No saved machines yet',
              message:
                  'Add an SSH, ADB, or scrcpy machine and Conduit will store '
                  "its configuration in your device's secure storage.",
              actionLabel: 'Add machine',
              onAction: _openForm,
            ),
          ]),
        ),
      );
    }

    final filteredHosts = _filteredHosts(controller.sortedHosts);
    final tags = _tagsFor(controller.hosts);
    final isFiltered = _query.trim().isNotEmpty || _selectedTag != null;
    final canReorder =
        controller.sortMode == HostListSortMode.manual && !isFiltered;

    return SliverPadding(
      padding: const EdgeInsets.fromLTRB(18, 0, 18, 120),
      sliver: SliverMainAxisGroup(
        slivers: [
          SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const _MachineSectionHeader(),
                const SizedBox(height: 12),
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: HostSearchField(
                        controller: _searchController,
                        onChanged: (value) => setState(() => _query = value),
                        hasContent: _query.isNotEmpty || _selectedTag != null,
                        onClear: _clearFilters,
                      ),
                    ),
                    const SizedBox(width: 8),
                    _HostSortMenu(
                      value: controller.sortMode,
                      onChanged: widget.hostsController.setSortMode,
                    ),
                  ],
                ),
                if (tags.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  TagFilterBar(
                    tags: tags,
                    selectedTag: _selectedTag,
                    onSelected: (tag) {
                      setState(() {
                        _selectedTag = _selectedTag == tag ? null : tag;
                      });
                    },
                  ),
                ],
                const SizedBox(height: 16),
              ],
            ),
          ),
          if (filteredHosts.isEmpty)
            SliverToBoxAdapter(
              child: MessageState(
                icon: Icons.search_off,
                title: 'No matches',
                message: 'Try a different search or clear filters.',
                actionLabel: 'Clear',
                onAction: _clearFilters,
              ),
            )
          else if (canReorder)
            SliverReorderableList(
              itemCount: filteredHosts.length,
              onReorderItem: widget.hostsController.reorderManual,
              proxyDecorator: _reorderProxyDecorator,
              itemBuilder: (context, index) {
                final host = filteredHosts[index];
                return Padding(
                  key: ValueKey(host.id),
                  padding: const EdgeInsets.only(bottom: 8),
                  child: _buildHostCard(
                    host,
                    dragHandle: ReorderableDragStartListener(
                      index: index,
                      child: const Padding(
                        padding: EdgeInsets.symmetric(horizontal: 4),
                        child: Icon(Icons.drag_handle_rounded),
                      ),
                    ),
                  ),
                );
              },
            )
          else
            SliverList.list(
              children: [
                for (final host in filteredHosts) ...[
                  _buildHostCard(host),
                  const SizedBox(height: 8),
                ],
              ],
            ),
        ],
      ),
    );
  }

  Widget _buildHostCard(SavedHost host, {Widget? dragHandle}) {
    return HostCard(
      host: host,
      active: widget.workspaceController.sessions.any(
        (session) => session.host.id == host.id,
      ),
      selectedTag: _selectedTag,
      onConnect: () => _connect(host),
      onAction: (action) => unawaited(_handleHostAction(action, host)),
      onTagTap: (tag) {
        setState(() {
          _selectedTag = _selectedTag == tag ? null : tag;
        });
      },
      dragHandle: dragHandle,
    );
  }

  Widget _reorderProxyDecorator(
    Widget child,
    int index,
    Animation<double> animation,
  ) {
    return AnimatedBuilder(
      animation: animation,
      builder: (context, child) {
        final lift = Curves.easeOut.transform(animation.value);
        return Transform.scale(
          scale: 1 + (0.015 * lift),
          child: Material(
            color: Colors.transparent,
            elevation: 8 * lift,
            shadowColor: Colors.black.withValues(alpha: 0.22),
            borderRadius: BorderRadius.circular(14),
            child: child,
          ),
        );
      },
      child: child,
    );
  }

  List<SavedHost> _filteredHosts(List<SavedHost> hosts) {
    final normalizedQuery = _query.trim().toLowerCase();
    return hosts.where((host) {
      final matchesTag =
          _selectedTag == null || host.tags.contains(_selectedTag);
      final matchesQuery =
          normalizedQuery.isEmpty ||
          host.name.toLowerCase().contains(normalizedQuery) ||
          host.host.toLowerCase().contains(normalizedQuery) ||
          host.username.toLowerCase().contains(normalizedQuery) ||
          host.protocol.name.toLowerCase().contains(normalizedQuery) ||
          host.tags.any((tag) => tag.toLowerCase().contains(normalizedQuery));
      return matchesTag && matchesQuery;
    }).toList();
  }

  List<String> _tagsFor(List<SavedHost> hosts) {
    final tags = <String>{};
    for (final host in hosts) {
      for (final tag in host.tags) {
        final trimmed = tag.trim();
        if (trimmed.isNotEmpty) tags.add(trimmed);
      }
    }
    final list = tags.toList();
    list.sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
    return list;
  }

  void _clearFilters() {
    _searchController.clear();
    setState(() {
      _query = '';
      _selectedTag = null;
    });
  }

  Future<void> _openTerminalWorkspace() async {
    if (_terminalPageOpen) return;
    _terminalPageOpen = true;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => TerminalPage(
          workspace: widget.workspaceController,
          themeController: widget.themeController,
        ),
      ),
    );
    _terminalPageOpen = false;
  }

  Future<void> _openLocalShellSetup() async {
    final request = await Navigator.of(context).push<LocalShellSetupRequest>(
      MaterialPageRoute(
        builder: (_) =>
            LocalShellSetupPage(controller: widget.localShellController),
      ),
    );
    if (request == null) return;
    unawaited(
      widget.localShellController.installNew(
        request.distroId,
        name: request.name,
      ),
    );
  }

  void _openLocalShellInstance(LocalShellInstance instance) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => LocalShellInstancePage(
          controller: widget.localShellController,
          instanceId: instance.id,
          onOpenSession: (instance) =>
              _openLocalSession(instance, forceNew: true),
          onCloseSessions: _closeLocalSession,
        ),
      ),
    );
  }

  Future<void> _openLocalSession(
    LocalShellInstance instance, {
    bool forceNew = false,
  }) async {
    if (widget.localShellController.sharedStorageFeatureEnabled &&
        !widget.localShellController.sharedStorageAccessGranted) {
      await widget.localShellController.requestSharedStorageAccess();
      if (!widget.localShellController.sharedStorageAccessGranted) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Grant file access, then open the shell again.'),
          ),
        );
        return;
      }
    }
    final existing = widget.workspaceController.sessions
        .where(
          (session) =>
              localShellInstanceIdFromHostId(session.host.id) == instance.id,
        )
        .toList();
    if (!forceNew && existing.isNotEmpty) {
      widget.workspaceController.activate(existing.first);
    } else {
      widget.workspaceController.open(
        widget.localShellController.localHost(
          instance,
          sessionNumber: existing.length + 1,
        ),
      );
    }
    unawaited(widget.localShellController.markOpened(instance.id));
    if (!mounted) return;
    await _openTerminalWorkspace();
  }

  Future<void> _closeLocalSession(String instanceId) async {
    final sessions = widget.workspaceController.sessions.where(
      (session) =>
          localShellInstanceIdFromHostId(session.host.id) == instanceId,
    );
    for (final session in List.of(sessions)) {
      await widget.workspaceController.close(session);
    }
  }

  Future<void> _openTrustedKeys() async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => TrustedKeysPage(
          verifier: widget.hostKeyVerifier,
          themeController: widget.themeController,
        ),
      ),
    );
  }

  Future<void> _openFiles(SavedHost host) async {
    await widget.hostsController.markConnected(host);
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => SftpBrowserPage(
          host: host,
          repository: widget.sftpRepository,
          fileExport: widget.fileExport,
          themeController: widget.themeController,
        ),
      ),
    );
  }

  Future<void> _connect(SavedHost host) async {
    await widget.hostsController.markConnected(host);
    if (!mounted) return;
    switch (host.protocol) {
      case MachineProtocol.ssh:
        widget.workspaceController.open(host);
        await _openTerminalWorkspace();
      case MachineProtocol.adb:
        await _openAdbOrScrcpySession(host);
      case MachineProtocol.scrcpy:
        await _launchScrcpySession(host);
    }
  }

  Future<void> _launchScrcpySession(SavedHost host) async {
    try {
      await widget.adbClient.startScrcpy(
        host: host.host.trim(),
        port: host.port,
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Failed to start scrcpy: $e')));
    }
  }

  Future<void> _openAdbOrScrcpySession(SavedHost host) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (_) =>
          _AdbScrcpySessionSheet(host: host, adbClient: widget.adbClient),
    );
  }

  Future<void> _lock() async {
    await widget.workspaceController.closeAll();
    widget.lockController.lock();
  }

  Future<void> _openForm([SavedHost? host]) async {
    final savedHost = await Navigator.of(context).push<SavedHost>(
      MaterialPageRoute(
        builder: (_) =>
            HostFormPage(host: host, themeController: widget.themeController),
      ),
    );
    if (savedHost != null) {
      await widget.hostsController.upsert(savedHost);
    }
  }

  Future<void> _handleHostAction(HostAction action, SavedHost host) async {
    switch (action) {
      case HostAction.files:
        await _openFiles(host);
      case HostAction.edit:
        await _openForm(host);
      case HostAction.duplicate:
        await _duplicate(host);
      case HostAction.copyAddress:
        await Clipboard.setData(ClipboardData(text: host.endpoint));
        if (!mounted) return;
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Copied ${host.endpoint}')));
      case HostAction.delete:
        await _confirmDelete(host);
    }
  }

  Future<void> _duplicate(SavedHost host) async {
    final keepSecrets = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Duplicate machine'),
        content: const Text(
          'Copy the saved password and key material into the new machine?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Without secrets'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Copy secrets'),
          ),
        ],
      ),
    );
    if (keepSecrets == null) return;
    final base = keepSecrets
        ? host
        : host.copyWith(password: '', privateKey: '', passphrase: '');
    await widget.hostsController.upsert(
      base.copyWith(
        id: const Uuid().v4(),
        name: '"${host.name}" Copy',
        clearLastConnectedAt: true,
      ),
    );
  }

  Future<void> _confirmDelete(SavedHost host) async {
    final shouldDelete = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete machine?'),
        content: Text('Conduit will forget "${host.name}".'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
            ),
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Delete'),
          ),
        ],
      ),
    );
    if (shouldDelete ?? false) {
      await widget.hostsController.remove(host);
    }
  }
}

class _MachineSectionHeader extends StatelessWidget {
  const _MachineSectionHeader();

  @override
  Widget build(BuildContext context) {
    return const Text('Saved machines');
  }
}

class _HostSortMenu extends StatelessWidget {
  const _HostSortMenu({required this.value, required this.onChanged});

  final HostListSortMode value;
  final ValueChanged<HostListSortMode> onChanged;

  @override
  Widget build(BuildContext context) {
    return PopupMenuButton<HostListSortMode>(
      tooltip: 'Sort machines',
      initialValue: value,
      onSelected: onChanged,
      icon: const Icon(Icons.sort_rounded),
      itemBuilder: (context) => const [
        PopupMenuItem(
          value: HostListSortMode.lastConnected,
          child: Text('Recently connected'),
        ),
        PopupMenuItem(value: HostListSortMode.name, child: Text('Name (A-Z)')),
        PopupMenuItem(value: HostListSortMode.added, child: Text('Date added')),
        PopupMenuItem(
          value: HostListSortMode.manual,
          child: Text('Manual order'),
        ),
      ],
    );
  }
}

class _AdbQuickKey {
  const _AdbQuickKey({
    required this.label,
    required this.command,
    required this.icon,
  });

  final String label;
  final String command;
  final IconData icon;
}

class _AdbScrcpySessionSheet extends StatefulWidget {
  const _AdbScrcpySessionSheet({required this.host, required this.adbClient});

  final SavedHost host;
  final NexusAdbClient adbClient;

  @override
  State<_AdbScrcpySessionSheet> createState() => _AdbScrcpySessionSheetState();
}

class _AdbScrcpySessionSheetState extends State<_AdbScrcpySessionSheet> {
  static const List<_AdbQuickKey> _quickKeys = [
    _AdbQuickKey(
      label: 'Battery',
      command: 'dumpsys battery',
      icon: Icons.battery_std_rounded,
    ),
    _AdbQuickKey(
      label: 'Device info',
      command:
          'echo "Model: \$(getprop ro.product.model)"; echo "Android: \$(getprop ro.build.version.release)"; echo "Display: \$(wm size) @ \$(wm density)dpi"; echo "Kernel: \$(uname -r)"',
      icon: Icons.info_outline_rounded,
    ),
    _AdbQuickKey(
      label: 'IP / Wi-Fi',
      command: 'ip -4 addr show wlan0 2>/dev/null || ip addr show',
      icon: Icons.wifi_rounded,
    ),
    _AdbQuickKey(
      label: 'Processes',
      command: 'ps -A | head -n 25',
      icon: Icons.memory_rounded,
    ),
    _AdbQuickKey(
      label: 'Storage',
      command: 'df -h /data /sdcard 2>/dev/null || df -h',
      icon: Icons.storage_rounded,
    ),
  ];

  final FocusNode _terminalFocus = FocusNode();
  final ScrollController _scrollController = ScrollController();
  final List<String> _lines = [];
  String _currentInput = '';
  bool _busy = false;
  String _promptPrefix = 'adb';

  @override
  void initState() {
    super.initState();
    _promptPrefix = '${widget.host.host}:${widget.host.port}';
    unawaited(_connectAndProbe());
  }

  @override
  void dispose() {
    _terminalFocus.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _appendLine(String text) {
    if (!mounted) return;
    setState(() {
      for (final rawLine in text.split('\n')) {
        final cleaned = rawLine.replaceAll('\r', '');
        if (cleaned.isNotEmpty || _lines.isEmpty) {
          _lines.add(cleaned);
        }
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 120),
        curve: Curves.easeOut,
      );
    });
  }

  void _runQuickKey(_AdbQuickKey item) {
    unawaited(_executeCommand(item.command));
  }

  void _clearTerminal() {
    if (!mounted) return;
    setState(_lines.clear);
  }

  Future<void> _connectAndProbe() async {
    if (_busy) return;
    setState(() => _busy = true);
    _appendLine(
      'Connecting (${widget.host.protocol.name}) to ${widget.host.host}:${widget.host.port}...',
    );
    try {
      final res = await widget.adbClient.testConnection(
        host: widget.host.host.trim(),
        port: widget.host.port,
      );
      final model = res.model.isEmpty ? 'device' : res.model;
      _promptPrefix = '$model@${res.host}:${res.port}';
      _appendLine('Connected to ${res.host}:${res.port} ($model)');
      if (widget.host.protocol == MachineProtocol.scrcpy) {
        _appendLine(
          'ADB transport ready for scrcpy session on ${res.host}:${res.port}.',
        );
      }
    } catch (e) {
      _appendLine('ADB error: $e');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        _terminalFocus.requestFocus();
      }
    }
  }

  Future<void> _executeCommand(String rawCommand) async {
    final cmd = rawCommand.trim();
    if (cmd.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _currentInput = '';
    });
    _appendLine('$_promptPrefix\$ $cmd');
    try {
      final out = await widget.adbClient.shell(cmd);
      _appendLine(out.trim().isEmpty ? '(no output)' : out.trimRight());
    } catch (e) {
      _appendLine('Shell error: $e');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        _terminalFocus.requestFocus();
      }
    }
  }

  void _handleHardwareKeyEvent(KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) return;
    if (event.logicalKey == LogicalKeyboardKey.enter ||
        event.logicalKey == LogicalKeyboardKey.numpadEnter) {
      final cmd = _currentInput;
      unawaited(_executeCommand(cmd));
      return;
    }
    if (event.logicalKey == LogicalKeyboardKey.backspace) {
      if (_currentInput.isNotEmpty) {
        setState(() {
          _currentInput = _currentInput.substring(0, _currentInput.length - 1);
        });
      }
      return;
    }
    if (event.logicalKey == LogicalKeyboardKey.escape) {
      setState(() => _currentInput = '');
      return;
    }
    final char = event.character;
    if (char != null &&
        char.isNotEmpty &&
        !char.contains('\n') &&
        !char.contains('\r')) {
      setState(() {
        _currentInput = '$_currentInput$char';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final isScrcpy = widget.host.protocol == MachineProtocol.scrcpy;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;

    return SafeArea(
      child: Padding(
        padding: EdgeInsets.fromLTRB(14, 12, 14, 14 + bottomInset),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${widget.host.name} (${widget.host.protocol.name.toUpperCase()})',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      Text(
                        '${widget.host.host}:${widget.host.port}',
                        style: TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 12,
                          color: colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: 'Reconnect',
                  onPressed: _busy ? null : _connectAndProbe,
                  icon: const Icon(Icons.refresh_rounded, size: 20),
                ),
                IconButton(
                  tooltip: 'Clear terminal',
                  onPressed: _clearTerminal,
                  icon: const Icon(Icons.delete_sweep_outlined, size: 20),
                ),
              ],
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 36,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: _quickKeys.length,
                separatorBuilder: (_, _) => const SizedBox(width: 6),
                itemBuilder: (context, index) {
                  final keyItem = _quickKeys[index];
                  return ActionChip(
                    avatar: Icon(keyItem.icon, size: 15),
                    label: Text(keyItem.label),
                    onPressed: _busy
                        ? null
                        : _busy
                        ? null
                        : () => _runQuickKey(keyItem),
                  );
                },
              ),
            ),
            const SizedBox(height: 8),
            KeyboardListener(
              focusNode: _terminalFocus,
              autofocus: true,
              onKeyEvent: _handleHardwareKeyEvent,
              child: GestureDetector(
                onTap: _terminalFocus.requestFocus,
                child: Container(
                  height: 240,
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: colorScheme.surfaceContainerLowest,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: colorScheme.outlineVariant),
                  ),
                  child: ListView(
                    controller: _scrollController,
                    children: [
                      for (final line in _lines)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 2),
                          child: SelectableText(
                            line,
                            style: TextStyle(
                              fontFamily: 'monospace',
                              fontSize: 12,
                              color: colorScheme.onSurface,
                            ),
                          ),
                        ),
                      Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '$_promptPrefix\$ ',
                            style: TextStyle(
                              fontFamily: 'monospace',
                              fontSize: 12,
                              fontWeight: FontWeight.w700,
                              color: colorScheme.primary,
                            ),
                          ),
                          Expanded(
                            child: Text(
                              '$_currentInput\u258c',
                              style: TextStyle(
                                fontFamily: 'monospace',
                                fontSize: 12,
                                color: colorScheme.onSurface,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
            if (isScrcpy) ...[
              const SizedBox(height: 6),
              Text(
                'scrcpy ADB transport session active.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
