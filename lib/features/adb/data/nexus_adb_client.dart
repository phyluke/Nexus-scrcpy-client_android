import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/services.dart';

class AdbKeyInfo {
  const AdbKeyInfo({required this.hasKey, required this.fingerprint});

  final bool hasKey;
  final String fingerprint;

  factory AdbKeyInfo.fromMap(Map<dynamic, dynamic>? map) {
    return AdbKeyInfo(
      hasKey: map?['hasKey'] == true,
      fingerprint: (map?['fingerprint'] as String?) ?? '',
    );
  }
}

class AdbConnectionTestResult {
  const AdbConnectionTestResult({
    required this.connected,
    required this.host,
    required this.port,
    required this.fingerprint,
    required this.model,
  });

  final bool connected;
  final String host;
  final int port;
  final String fingerprint;
  final String model;

  factory AdbConnectionTestResult.fromMap(Map<dynamic, dynamic>? map) {
    return AdbConnectionTestResult(
      connected: map?['connected'] == true,
      host: (map?['host'] as String?) ?? '',
      port: (map?['port'] as int?) ?? 5555,
      fingerprint: (map?['fingerprint'] as String?) ?? '',
      model: ((map?['model'] as String?) ?? '').trim(),
    );
  }
}

class NexusAdbClient {
  const NexusAdbClient({this.channel = const MethodChannel('nexus/adb')});

  final MethodChannel channel;

  Future<AdbKeyInfo> ensureKey() async {
    final raw = await channel.invokeMethod('ensureKey');
    final map = raw is Map ? raw : null;
    return AdbKeyInfo.fromMap(map);
  }

  Future<AdbKeyInfo> generateKey() async {
    final raw = await channel.invokeMethod('generateKey');
    final map = raw is Map ? raw : null;
    return AdbKeyInfo.fromMap(map);
  }

  Future<AdbKeyInfo> importKeyPem(String pem) async {
    final raw = await channel.invokeMethod('importKey', {'pem': pem});
    final map = raw is Map ? raw : null;
    return AdbKeyInfo.fromMap(map);
  }

  Future<String> publicKey() async {
    final raw = await channel.invokeMethod('publicKey');
    return (raw is String ? raw : '').trim();
  }

  Future<AdbConnectionTestResult> testConnection({
    required String host,
    required int port,
  }) async {
    final raw = await channel.invokeMethod('testConnection', {
      'host': host,
      'port': port,
    });
    final map = raw is Map ? raw : null;
    return AdbConnectionTestResult.fromMap(map);
  }

  Future<String> shell(String command) async {
    final raw = await channel.invokeMethod('shell', {'command': command});
    return raw is String ? raw : '';
  }

  Future<bool> startScrcpy({
    required String host,
    int port = 5555,
    int maxSize = 1024,
    int bitRate = 2500000,
    int maxFps = 60,
    bool screenOff = false,
  }) async {
    final raw = await channel.invokeMethod('startScrcpy', {
      'host': host,
      'port': port,
      'maxSize': maxSize,
      'bitRate': bitRate,
      'maxFps': maxFps,
      'screenOff': screenOff,
    });
    return raw == true;
  }

  Future<void> disconnect() async {
    await channel.invokeMethod('disconnect');
  }
}

Future<String?> pickAdbKeyFileContent() async {
  final result = await FilePicker.pickFiles(withData: true);
  if (result == null || result.files.isEmpty) return null;
  final bytes = result.files.first.bytes;
  if (bytes == null) return null;
  return utf8.decode(bytes).trim();
}

Future<String?> exportAdbPublicKeyFile(String publicKeyLine) async {
  return FilePicker.saveFile(
    dialogTitle: 'Export ADB public key',
    fileName: 'adbkey.pub',
    bytes: Uint8List.fromList(utf8.encode('$publicKeyLine\n')),
  );
}
