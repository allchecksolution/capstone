import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'backend.dart';

abstract interface class RecordingQueue {
  Future<ApiData> scan();
  Future<Uint8List> read(String sourceId);
  Future<void> complete(String sourceId);
}

class AndroidRecordingQueue implements RecordingQueue {
  static const channel = MethodChannel('allcheck/platform');
  bool get supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;
  Future<ApiData> status() async => Map<String, dynamic>.from(
    await channel.invokeMapMethod<String, dynamic>(
          'recordingCollectionStatus',
        ) ??
        {},
  );
  Future<ApiData> configure({
    required bool enabled,
    bool includeExisting = false,
  }) async => Map<String, dynamic>.from(
    await channel.invokeMapMethod<String, dynamic>('setRecordingCollection', {
          'enabled': enabled,
          'includeExisting': includeExisting,
        }) ??
        {},
  );
  @override
  Future<ApiData> scan() async => Map<String, dynamic>.from(
    await channel.invokeMapMethod<String, dynamic>('scanRecordings') ?? {},
  );
  @override
  Future<Uint8List> read(String sourceId) async =>
      await channel.invokeMethod<Uint8List>('readCollectedRecording', {
        'sourceId': sourceId,
      }) ??
      (throw StateError('녹음을 읽지 못했습니다.'));
  @override
  Future<void> complete(String sourceId) => channel.invokeMethod<void>(
    'completeCollectedRecording',
    {'sourceId': sourceId},
  );
}

abstract interface class RecordingJournal {
  Future<ApiData> load();
  Future<void> save(ApiData state);
}

class LocalRecordingJournal implements RecordingJournal {
  final _prefs = SharedPreferencesAsync();
  static const _key = 'allcheck.recording.upload-journal.v1';
  @override
  Future<ApiData> load() async {
    final saved = await _prefs.getString(_key);
    return saved == null ? {} : jsonDecode(saved) as ApiData;
  }

  @override
  Future<void> save(ApiData state) => _prefs.setString(_key, jsonEncode(state));
}

/// Collecting is local and may run before sign-in. Sending requires an explicit
/// stable administrator identity supplied by the service authentication layer.
class RecordingSynchronizer {
  RecordingSynchronizer({
    required this.queue,
    required this.journal,
    required this.backend,
    required this.currentOwner,
  });
  final RecordingQueue queue;
  final RecordingJournal journal;
  final FrontendBackend backend;
  final String? Function() currentOwner;
  bool _running = false;

  Future<int> sync(ApiData snapshot) async {
    if (_running || snapshot['enabled'] != true) return 0;
    final owner = currentOwner();
    if (owner == null || owner.isEmpty) return 0;
    _running = true;
    try {
      final state = await journal.load();
      _ensureOwner(owner);
      var count = 0;
      var unreadable = false;
      for (final raw in snapshot['items'] as List? ?? []) {
        final item = Map<String, dynamic>.from(raw as Map);
        if (item['uploaded'] == true) continue;
        _ensureOwner(owner);
        final source = item['sourceId'] as String;
        final prior = state[source];
        if (prior is Map && prior['owner'] != owner) continue;
        final attempt = prior == null
            ? <String, dynamic>{
                'owner': owner,
                'createKey': _key(),
                'uploadKey': _key(),
              }
            : Map<String, dynamic>.from(prior as Map);
        state[source] = attempt;
        // Persist keys before the first network request: retry must never create
        // another reception if the first success response was lost.
        await journal.save(state);
        Uint8List bytes;
        try {
          bytes = await queue.read(source);
        } catch (_) {
          _ensureOwner(owner);
          unreadable = true;
          continue;
        }
        _ensureOwner(owner);
        final content = sha256
            .convert(utf8.encode('${item['name']}/${sha256.convert(bytes)}'))
            .toString();
        if (state.values.whereType<Map>().any(
          (a) =>
              a['owner'] == owner &&
              a['content'] == content &&
              a['done'] == true,
        )) {
          await queue.complete(source);
          continue;
        }
        attempt['content'] = content;
        ApiData reception;
        if (attempt['receptionId'] == null) {
          final created = await backend.call('receptions/create', {
            'requestId': attempt['createKey'],
          });
          _ensureOwner(owner);
          reception = created['consultation'] as ApiData;
          attempt['receptionId'] = reception['id'];
          await journal.save(state);
        } else {
          final found = await backend.call('receptions/detail', {
            'id': attempt['receptionId'],
          });
          _ensureOwner(owner);
          reception = found['consultation'] as ApiData;
        }
        if (reception['recordingStatus'] != 'SUCCEEDED') {
          await backend.call('receptions/recording-upload', {
            'id': reception['id'],
            'expectedVersion': reception['version'],
            'recordingFileId': reception['recordingFileId'],
            'bytes': bytes,
            'originalFilename': item['name'],
            'requestId':
                '${attempt['uploadKey']}${reception['recordingFileId'] == null ? '' : '-resume'}',
          });
          _ensureOwner(owner);
        }
        attempt['done'] = true;
        await journal.save(state);
        _ensureOwner(owner);
        await queue.complete(source);
        count++;
        // One file per tick bounds memory, server load and time on the UI session.
        break;
      }
      if (count == 0 && unreadable) {
        throw StateError('일부 녹음을 읽지 못했습니다. 원본 파일과 64MB 이하 크기를 확인해 주세요.');
      }
      return count;
    } finally {
      _running = false;
    }
  }

  void _ensureOwner(String owner) {
    if (currentOwner() != owner) {
      throw const ServiceFailure(499, '로그인 상태가 변경되어 녹음 전송을 중단했습니다.');
    }
  }

  static String _key() =>
      'rec-${DateTime.now().microsecondsSinceEpoch}-${Random.secure().nextInt(0x7fffffff)}';
}
