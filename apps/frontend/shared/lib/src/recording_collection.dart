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
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;
  final RecordingQueue queue;
  final RecordingJournal journal;
  final FrontendBackend backend;
  final String? Function() currentOwner;
  final DateTime Function() _now;
  bool _running = false;

  /// Files whose last attempt failed, keyed by source ID. Each entry keeps the
  /// failure count, last message and the earliest automatic retry time.
  Map<String, ApiData> failures = {};

  static const _firstRetry = Duration(minutes: 1);
  static const _maxRetry = Duration(minutes: 30);

  /// Uploads at most one file per call. A file-specific rejection is recorded
  /// and backed off so the remaining files keep moving. Server errors (5xx)
  /// are also backed off but stop the tick; session, permission and network
  /// failures stop it without back-off so the same file is retried first.
  /// [retryFailed] ignores the back-off for an explicit user retry.
  Future<int> sync(ApiData snapshot, {bool retryFailed = false}) async {
    if (_running || snapshot['enabled'] != true) return 0;
    final owner = currentOwner();
    if (owner == null || owner.isEmpty) return 0;
    _running = true;
    ApiData state = {};
    try {
      state = await journal.load();
      _ensureOwner(owner);
      var count = 0;
      var unreadable = false;
      final now = _now();
      for (final raw in snapshot['items'] as List? ?? []) {
        final item = Map<String, dynamic>.from(raw as Map);
        if (item['uploaded'] == true) continue;
        _ensureOwner(owner);
        final source = item['sourceId'] as String;
        final prior = state[source];
        if (prior is Map && prior['owner'] != owner) continue;
        final retryAt = prior is Map
            ? DateTime.tryParse('${prior['retryAt'] ?? ''}')
            : null;
        if (!retryFailed && retryAt != null && retryAt.isAfter(now)) continue;
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
        try {
          await _send(owner, state, attempt, item, bytes);
        } on ServiceFailure catch (error) {
          final fileSpecific = _fileSpecific(error);
          // A 5xx may be caused by this file; back it off but stop the tick.
          if (!fileSpecific && error.status < 500) rethrow;
          _ensureOwner(owner);
          _recordFailure(attempt, error.message, now);
          await journal.save(state);
          if (!fileSpecific) rethrow;
          continue;
        }
        attempt
          ..['done'] = true
          ..remove('failures')
          ..remove('lastError')
          ..remove('retryAt');
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
      failures = _failures(state, owner);
      _running = false;
    }
  }

  Future<void> _send(
    String owner,
    ApiData state,
    ApiData attempt,
    ApiData item,
    Uint8List bytes,
  ) async {
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
  }

  /// 4xx rejections concern this file or reception. Authentication, permission,
  /// cancellation, network (0) and 5xx failures affect every file.
  static bool _fileSpecific(ServiceFailure error) =>
      error.status >= 400 &&
      error.status < 500 &&
      ![401, 403, 499].contains(error.status);

  static void _recordFailure(ApiData attempt, String message, DateTime now) {
    final failures = (attempt['failures'] as int? ?? 0) + 1;
    var delay = _firstRetry * (1 << (failures - 1).clamp(0, 10));
    if (delay > _maxRetry) delay = _maxRetry;
    attempt
      ..['failures'] = failures
      ..['lastError'] = message
      ..['retryAt'] = now.add(delay).toUtc().toIso8601String();
  }

  static Map<String, ApiData> _failures(ApiData state, String owner) => {
    for (final entry in state.entries)
      if (entry.value case final Map attempt
          when attempt['owner'] == owner &&
              attempt['done'] != true &&
              attempt['lastError'] != null)
        entry.key: {
          'failures': attempt['failures'],
          'message': attempt['lastError'],
          'retryAt': attempt['retryAt'],
        },
  };

  void _ensureOwner(String owner) {
    if (currentOwner() != owner) {
      throw const ServiceFailure(499, '로그인 상태가 변경되어 녹음 전송을 중단했습니다.');
    }
  }

  static String _key() =>
      'rec-${DateTime.now().microsecondsSinceEpoch}-${Random.secure().nextInt(0x7fffffff)}';
}
