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
}
