import 'package:conduit/features/adb/data/nexus_adb_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

Future<void> showAdbKeysSheet({
  required BuildContext context,
  NexusAdbClient client = const NexusAdbClient(),
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (context) => _AdbKeysSheetBody(client: client),
  );
}

class _AdbKeysSheetBody extends StatefulWidget {
  const _AdbKeysSheetBody({required this.client});

  final NexusAdbClient client;

  @override
  State<_AdbKeysSheetBody> createState() => _AdbKeysSheetBodyState();
}

class _AdbKeysSheetBodyState extends State<_AdbKeysSheetBody> {
  AdbKeyInfo? _info;
  String _publicKey = '';
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final info = await widget.client.ensureKey();
      final pub = await widget.client.publicKey();
      if (!mounted) return;
      setState(() {
        _info = info;
        _publicKey = pub;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _generate() async {
    try {
      final info = await widget.client.generateKey();
      final pub = await widget.client.publicKey();
      if (!mounted) return;
      setState(() {
        _info = info;
        _publicKey = pub;
        _error = null;
      });
      _snack('Generated a new ADB key pair');
    } catch (e) {
      if (!mounted) return;
      _snack('Failed to generate key: $e');
    }
  }

  Future<void> _importFromFile() async {
    try {
      final pem = await pickAdbKeyFileContent();
      if (pem == null || pem.isEmpty) return;
      final info = await widget.client.importKeyPem(pem);
      final pub = await widget.client.publicKey();
      if (!mounted) return;
      setState(() {
        _info = info;
        _publicKey = pub;
        _error = null;
      });
      _snack('Imported ADB key (not added to trusted hosts)');
    } catch (e) {
      if (!mounted) return;
      _snack('Import failed: $e');
    }
  }

  Future<void> _importFromPasteDialog() async {
    final controller = TextEditingController();
    final pem = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Import ADB private key (PKCS#8 PEM)'),
        content: TextField(
          controller: controller,
          maxLines: 6,
          decoration: const InputDecoration(
            hintText: '-----BEGIN PRIVATE KEY-----\n...',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(controller.text.trim()),
            child: const Text('Import'),
          ),
        ],
      ),
    ).whenComplete(controller.dispose);
    if (pem == null || pem.isEmpty) return;
    try {
      final info = await widget.client.importKeyPem(pem);
      final pub = await widget.client.publicKey();
      if (!mounted) return;
      setState(() {
        _info = info;
        _publicKey = pub;
        _error = null;
      });
      _snack('Imported ADB key (not added to trusted hosts)');
    } catch (e) {
      if (!mounted) return;
      _snack('Import failed: $e');
    }
  }

  Future<void> _copyPublicKey() async {
    if (_publicKey.isEmpty) return;
    await Clipboard.setData(ClipboardData(text: _publicKey));
    if (mounted) _snack('Copied ADB public key');
  }

  Future<void> _exportPublicKey() async {
    if (_publicKey.isEmpty) return;
    try {
      final savedPath = await exportAdbPublicKeyFile(_publicKey);
      if (!mounted || savedPath == null) return;
      _snack('Exported adbkey.pub');
    } catch (e) {
      if (!mounted) return;
      _snack('Export failed: $e');
    }
  }

  void _snack(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: EdgeInsets.fromLTRB(
        18,
        16,
        18,
        24 + MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('ADB keys', style: theme.textTheme.titleLarge),
          const SizedBox(height: 4),
          Text(
            'Used for ADB and scrcpy authentication. Importing a key does not '
            'mark any host as trusted.',
            style: theme.textTheme.bodySmall,
          ),
          const SizedBox(height: 14),
          if (_loading)
            const Center(child: CircularProgressIndicator())
          else if (_error != null)
            Text(_error!, style: TextStyle(color: theme.colorScheme.error))
          else ...[
            Text('Fingerprint', style: theme.textTheme.labelMedium),
            const SizedBox(height: 4),
            SelectableText(
              (_info?.fingerprint.isEmpty ?? true)
                  ? 'No key generated'
                  : _info!.fingerprint,
              style: const TextStyle(fontFamily: 'monospace'),
            ),
            const SizedBox(height: 12),
            Text('Public key (adbkey.pub)', style: theme.textTheme.labelMedium),
            const SizedBox(height: 4),
            SelectableText(
              _publicKey.isEmpty ? 'Unavailable' : _publicKey,
              maxLines: 3,
              style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                OutlinedButton.icon(
                  onPressed: _copyPublicKey,
                  icon: const Icon(Icons.copy_rounded, size: 18),
                  label: const Text('Copy public key'),
                ),
                OutlinedButton.icon(
                  onPressed: _exportPublicKey,
                  icon: const Icon(Icons.download_rounded, size: 18),
                  label: const Text('Export adbkey.pub'),
                ),
                OutlinedButton.icon(
                  onPressed: _importFromFile,
                  icon: const Icon(Icons.file_open_outlined, size: 18),
                  label: const Text('Import file'),
                ),
                OutlinedButton.icon(
                  onPressed: _importFromPasteDialog,
                  icon: const Icon(Icons.paste_rounded, size: 18),
                  label: const Text('Paste PEM'),
                ),
                FilledButton.icon(
                  onPressed: _generate,
                  icon: const Icon(Icons.vpn_key_rounded, size: 18),
                  label: const Text('Generate key'),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
