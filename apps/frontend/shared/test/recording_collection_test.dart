import 'dart:convert';
import 'dart:typed_data';

import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter_test/flutter_test.dart';

class MemoryJournal implements RecordingJournal {
  ApiData state = {};
  @override
  Future<ApiData> load() async => jsonDecode(jsonEncode(state)) as ApiData;
  @override
  Future<void> save(ApiData data) async {
    state = jsonDecode(jsonEncode(data)) as ApiData;
  }
}

class TestQueue implements RecordingQueue {
  final completed = <String>{};
  @override
  Future<ApiData> scan() async => {};
  @override
  Future<Uint8List> read(String id) async => Uint8List.fromList([1, 2, 3]);
  @override
  Future<void> complete(String id) async {
    completed.add(id);
  }
}

class TestApi implements FrontendBackend {
  final requests = <ApiData>[];
  bool loseCreate = false;
  bool uploaded = false;
  void Function()? duringCreate;
  @override
  void clearSession() {}
  @override
  Future<ApiData> call(String operation, [ApiData payload = const {}]) async {
    requests.add({'operation': operation, ...payload});
    if (operation == 'receptions/create') {
      duringCreate?.call();
      if (loseCreate) {
        loseCreate = false;
        throw const ServiceFailure(0, '통신 오류');
      }
    }
    if (operation == 'receptions/recording-upload') {
      expect(payload['originalFilename'], 'recording.wav');
      uploaded = true;
    }
    return {
      'consultation': {
        'id': 'rec_1',
        'version': uploaded ? 3 : 1,
        'recordingStatus': uploaded ? 'SUCCEEDED' : 'NOT_AVAILABLE',
      },
    };
  }
}

ApiData snapshot([String source = 'source-1']) => {
  'enabled': true,
  'items': [
    {
      'sourceId': source,
      'name': 'recording.wav',
      'sizeBytes': 3,
      'uploaded': false,
    },
  ],
};

/// Each source gets its own reception; uploads of [rejected] always fail.
class MultiFileApi implements FrontendBackend {
  MultiFileApi(this.rejected, {this.rejectStatus = 400});
  final String rejected;
  final int rejectStatus;
  final uploads = <String>[];
  var _next = 0;
  @override
  void clearSession() {}
  @override
  Future<ApiData> call(String operation, [ApiData payload = const {}]) async {
    switch (operation) {
      case 'receptions/create':
        final id = 'rec_${_next++}';
        return {
          'consultation': {
            'id': id,
            'version': 1,
            'recordingStatus': 'NOT_AVAILABLE',
          },
        };
      case 'receptions/detail':
        return {
          'consultation': {
            'id': payload['id'],
            'version': 1,
            'recordingStatus': 'NOT_AVAILABLE',
          },
        };
      case 'receptions/recording-upload':
        if (payload['originalFilename'] == rejected) {
          throw ServiceFailure(rejectStatus, '업로드 거부');
        }
        uploads.add(payload['originalFilename'] as String);
        return {};
    }
    throw StateError(operation);
  }
}

class NamedQueue extends TestQueue {
  @override
  Future<Uint8List> read(String id) async =>
      Uint8List.fromList(utf8.encode(id));
}

ApiData twoFiles({Set<String> uploaded = const {}}) => {
  'enabled': true,
  'items': [
    for (final name in ['bad.wav', 'good.wav'])
      {
        'sourceId': name,
        'name': name,
        'sizeBytes': 3,
        'uploaded': uploaded.contains(name),
      },
  ],
};

void main() {
  test('no server traffic without service administrator identity', () async {
    final api = TestApi(), queue = TestQueue();
    final service = RecordingSynchronizer(
      queue: queue,
      journal: MemoryJournal(),
      backend: api,
      currentOwner: () => null,
    );
    expect(await service.sync(snapshot()), 0);
    expect(api.requests, isEmpty);
    expect(queue.completed, isEmpty);
  });
  test(
    'collecting creates one reception and uploads original filename before acknowledging',
    () async {
      final api = TestApi(), queue = TestQueue(), journal = MemoryJournal();
      final service = RecordingSynchronizer(
        queue: queue,
        journal: journal,
        backend: api,
        currentOwner: () => 'server|admin1',
      );
      expect(await service.sync(snapshot()), 1);
      expect(api.requests.map((e) => e['operation']), [
        'receptions/create',
        'receptions/recording-upload',
      ]);
      expect(journal.state['source-1']['done'], true);
      expect(queue.completed, {'source-1'});
    },
  );
  test(
    'restart after lost creation response replays the persisted idempotency key',
    () async {
      final api = TestApi()..loseCreate = true;
      final queue = TestQueue(), journal = MemoryJournal();
      RecordingSynchronizer make() => RecordingSynchronizer(
        queue: queue,
        journal: journal,
        backend: api,
        currentOwner: () => 'admin1',
      );
      await expectLater(
        make().sync(snapshot()),
        throwsA(isA<ServiceFailure>()),
      );
      expect(queue.completed, isEmpty);
      expect(journal.state['source-1']['receptionId'], isNull);
      expect(await make().sync(snapshot()), 1);
      final creates = api.requests
          .where((e) => e['operation'] == 'receptions/create')
          .toList();
      expect(creates.length, 2);
      expect(creates.first['requestId'], creates.last['requestId']);
    },
  );
  test(
    'existing reception resumes instead of duplicating, accepted recording is not uploaded again',
    () async {
      final journal = MemoryJournal()
        ..state = {
          'source-1': {
            'owner': 'admin1',
            'createKey': 'create',
            'uploadKey': 'upload',
            'receptionId': 'rec_1',
          },
        };
      final api = TestApi()..uploaded = true;
      final queue = TestQueue();
      final service = RecordingSynchronizer(
        queue: queue,
        journal: journal,
        backend: api,
        currentOwner: () => 'admin1',
      );
      expect(await service.sync(snapshot()), 1);
      expect(api.requests.map((e) => e['operation']), ['receptions/detail']);
      expect(queue.completed, {'source-1'});
    },
  );
  test(
    'same filename and content with new filesystem metadata is not uploaded twice',
    () async {
      final api = TestApi(), journal = MemoryJournal(), queue = TestQueue();
      final service = RecordingSynchronizer(
        queue: queue,
        journal: journal,
        backend: api,
        currentOwner: () => 'admin1',
      );
      await service.sync(snapshot());
      await service.sync(snapshot('changed-metadata'));
      expect(api.requests.length, 2);
      expect(queue.completed, {'source-1', 'changed-metadata'});
    },
  );
  test(
    'another administrator cannot resume someone else unfinished reception',
    () async {
      final journal = MemoryJournal()
        ..state = {
          'source-1': {'owner': 'admin1', 'receptionId': 'rec_1'},
        };
      final api = TestApi(), queue = TestQueue();
      final service = RecordingSynchronizer(
        queue: queue,
        journal: journal,
        backend: api,
        currentOwner: () => 'admin2',
      );
      expect(await service.sync(snapshot()), 0);
      expect(api.requests, isEmpty);
    },
  );
  test('logout between requests stops upload and keeps item pending', () async {
    String? owner = 'admin1';
    final api = TestApi()
      ..duringCreate = () {
        owner = null;
      };
    final queue = TestQueue(), journal = MemoryJournal();
    final service = RecordingSynchronizer(
      queue: queue,
      journal: journal,
      backend: api,
      currentOwner: () => owner,
    );
    await expectLater(
      service.sync(snapshot()),
      throwsA(isA<ServiceFailure>().having((e) => e.status, 'status', 499)),
    );
    expect(api.requests.length, 1);
    expect(queue.completed, isEmpty);
  });
  test(
    'a file rejected by the server does not block the remaining files',
    () async {
      var now = DateTime.utc(2026, 11, 27, 9);
      final api = MultiFileApi('bad.wav');
      final queue = NamedQueue(), journal = MemoryJournal();
      final service = RecordingSynchronizer(
        queue: queue,
        journal: journal,
        backend: api,
        currentOwner: () => 'admin1',
        now: () => now,
      );
      expect(await service.sync(twoFiles()), 1);
      expect(api.uploads, ['good.wav']);
      expect(queue.completed, {'good.wav'});
      expect(service.failures.keys, ['bad.wav']);
      expect(service.failures['bad.wav']!['failures'], 1);
      expect(journal.state['bad.wav']['done'], isNull);

      // Within the back-off window the failed file is not retried.
      final pending = twoFiles(uploaded: {'good.wav'});
      for (var i = 0; i < 3; i++) {
        expect(await service.sync(pending), 0);
      }
      expect(journal.state['bad.wav']['failures'], 1);

      // After the back-off it is retried with the same reception and keys.
      now = now.add(const Duration(minutes: 2));
      expect(await service.sync(pending), 0);
      expect(journal.state['bad.wav']['failures'], 2);
      expect(journal.state['bad.wav']['receptionId'], 'rec_0');

      // An explicit retry ignores the back-off window.
      expect(await service.sync(pending, retryFailed: true), 0);
      expect(journal.state['bad.wav']['failures'], 3);
    },
  );
  test('session and network failures stop the tick without back-off', () async {
    for (final status in [401, 403, 0]) {
      final api = MultiFileApi('bad.wav', rejectStatus: status);
      final queue = NamedQueue(), journal = MemoryJournal();
      final service = RecordingSynchronizer(
        queue: queue,
        journal: journal,
        backend: api,
        currentOwner: () => 'admin1',
      );
      await expectLater(
        service.sync(twoFiles()),
        throwsA(isA<ServiceFailure>().having((e) => e.status, 'status', status)),
      );
      expect(api.uploads, isEmpty);
      expect(journal.state['bad.wav']['retryAt'], isNull);
      expect(service.failures, isEmpty);
    }
  });
  test('a server error backs the file off and stops the tick', () async {
    final api = MultiFileApi('bad.wav', rejectStatus: 503);
    final queue = NamedQueue(), journal = MemoryJournal();
    final service = RecordingSynchronizer(
      queue: queue,
      journal: journal,
      backend: api,
      currentOwner: () => 'admin1',
    );
    await expectLater(
      service.sync(twoFiles()),
      throwsA(isA<ServiceFailure>().having((e) => e.status, 'status', 503)),
    );
    expect(api.uploads, isEmpty);
    expect(service.failures.keys, ['bad.wav']);
    expect(await service.sync(twoFiles()), 1);
    expect(api.uploads, ['good.wav']);
  });
}
