import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

Map<String, dynamic> detail({
  int version = 1,
  String reviewStatus = 'DRAFT',
  bool editable = true,
}) => {
  'receptionId': 'rec_1',
  'version': version,
  'reviewStatus': reviewStatus,
  'recordingStatus': 'NOT_AVAILABLE',
  'sttStatus': 'NOT_AVAILABLE',
  'transcriptStorageStatus': 'NOT_AVAILABLE',
  'analysisStatus': 'NOT_AVAILABLE',
  'reviewEditable': editable,
  'callerPhoneMasked': '*******1234',
  'transcript': '보관된 상담 원문',
  'aiOriginal': {'symptoms': '최초 분석 증상'},
  'finalData': {
    'workType': 'BLOCKAGE',
    'symptoms': '검토한 증상',
    'address': '방문 주소',
    'contactPhone': '01012345678',
  },
};

http.Response jsonResponse(Object body, [int code = 200]) => http.Response(
  jsonEncode(body),
  code,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

void main() {
  test(
    'server origin rejects paths, credentials and insecure remote hosts',
    () {
      for (final url in [
        'https://api.example.com/api/frontend',
        'https://user:pass@api.example.com',
        'http://example.com',
      ]) {
        expect(() => SpringFrontendBackend(url), throwsArgumentError);
      }
    },
  );

  test(
    'unsupported login does not send credentials to a guessed endpoint',
    () async {
      var requests = 0;
      final api = SpringFrontendBackend(
        'https://api.example.com',
        client: MockClient((r) async {
          requests++;
          return jsonResponse({});
        }),
      );
      await expectLater(
        api.call('auth/admin-login', {'password': 'private'}),
        throwsA(isA<ServiceFailure>().having((e) => e.status, 'status', 501)),
      );
      expect(requests, 0);
      api.close();
    },
  );

  test('list uses implemented GET route, bearer and page metadata', () async {
    final api = SpringFrontendBackend(
      'https://api.example.com',
      client: MockClient((r) async {
        expect(r.method, 'GET');
        expect(r.url.path, '/api/v1/receptions');
        expect(r.url.queryParameters, {'page': '2', 'size': '20'});
        expect(r.headers['Authorization'], 'Bearer service-token');
        expect(r.followRedirects, false);
        return jsonResponse({
          'items': [detail()],
          'page': {'page': 2, 'totalPages': 3},
        });
      }),
    )..setServiceSession('service-token');
    final result = await api.call('receptions/list', {'page': 2});
    expect(result['receptionPage']['page'], 2);
    expect(result['consultations'][0]['id'], 'rec_1');
    expect(result['consultations'][0]['status'], '접수 대기');
    api.close();
  });

  test('detail preserves AI original and editable fields separately', () async {
    final api = SpringFrontendBackend(
      'https://api.example.com',
      client: MockClient((r) async => jsonResponse(detail())),
    )..setServiceSession('token');
    final c = (await api.call('receptions/detail', {
      'id': 'rec_1',
    }))['consultation'];
    expect(c['analysis']['symptoms'], '최초 분석 증상');
    expect(c['symptom'], '검토한 증상');
    expect(c['type'], '막힘');
    expect(c['stt'], '보관된 상담 원문');
    api.close();
  });

  test(
    'confirmation sends exact finalData enum, version and idempotency header',
    () async {
      final requests = <http.Request>[];
      final api = SpringFrontendBackend(
        'https://api.example.com',
        client: MockClient((r) async {
          requests.add(r);
          if (r.method == 'POST') {
            expect(r.url.path, '/api/v1/receptions/rec_1/confirmation');
            expect(r.headers['Idempotency-Key'], 'confirm-once');
            final body = jsonDecode(r.body);
            expect(body['expectedVersion'], 7);
            expect(body['finalData']['workType'], 'BLOCKAGE');
            expect(
              body['finalData']['desiredVisitFrom'],
              '2099-01-01T01:00:00.000Z',
            );
            expect(body['finalData'].containsKey('customer'), false);
            return jsonResponse({'version': 8});
          }
          return jsonResponse(
            detail(version: 8, reviewStatus: 'CONFIRMED', editable: false),
          );
        }),
      )..setServiceSession('token');
      final result = await api.call('receptions/review', {
        'id': 'rec_1',
        'expectedVersion': 7,
        'requestId': 'confirm-once',
        'fields': {
          'type': '막힘',
          'symptom': '증상',
          'address': '주소',
          'phone': '01012345678',
          'notes': '',
          'desiredVisitFrom': '2099-01-01T10:00:00+09:00',
          'desiredVisitTo': '2099-01-01T11:00:00+09:00',
        },
      });
      expect(result['consultation']['reviewed'], true);
      expect(result['consultation']['version'], 8);
      expect(requests.length, 2);
      api.close();
    },
  );

  test(
    'conflict and server error payload never expose private details',
    () async {
      final api = SpringFrontendBackend(
        'https://api.example.com',
        client: MockClient(
          (r) async => jsonResponse({
            'message': 'private token and customer address',
          }, 409),
        ),
      )..setServiceSession('token');
      await expectLater(
        api.call('receptions/reopen', {
          'id': 'rec_1',
          'expectedVersion': 2,
          'requestId': 'key',
        }),
        throwsA(
          isA<ServiceFailure>()
              .having((e) => e.status, 'status', 409)
              .having((e) => e.message, 'message', isNot(contains('private'))),
        ),
      );
      api.close();
    },
  );

  test(
    'upload uses raw filename, base64 SHA256, signed headers and grant version',
    () async {
      final bytes = Uint8List.fromList([82, 73, 70, 70, 1, 2, 3]);
      final digest = base64Encode(sha256.convert(bytes).bytes);
      final paths = <String>[];
      final api = SpringFrontendBackend(
        'https://api.example.com',
        client: MockClient((r) async {
          paths.add(r.url.path);
          if (r.url.path.endsWith('/upload-requests')) {
            final body = jsonDecode(r.body);
            expect(
              body['originalFilename'],
              'rec_표시명_01012345678@261120_100000.wav',
            );
            expect(body['checksum'], {'algorithm': 'SHA256', 'value': digest});
            expect(body['sizeBytes'], bytes.length);
            return jsonResponse({
              'recordingFileId': 'file_rec_1',
              'version': 3,
              'method': 'PUT',
              'uploadUrl':
                  'https://storage.example.com/object?signature=private',
              'requiredHeaders': {
                'Content-Type': 'audio/wav',
                'Content-Length': '${bytes.length}',
                'If-None-Match': '*',
                'x-amz-checksum-sha256': digest,
              },
            });
          }
          if (r.url.host == 'storage.example.com') {
            expect(r.method, 'PUT');
            expect(r.bodyBytes, bytes);
            expect(r.headers.containsKey('Authorization'), false);
            expect(r.headers['If-None-Match'], '*');
            expect(r.headers['x-amz-checksum-sha256'], digest);
            expect(r.followRedirects, false);
            return http.Response('', 200);
          }
          if (r.url.path.endsWith('/completion')) {
            expect(jsonDecode(r.body), {'expectedVersion': 3});
            expect(r.headers['Idempotency-Key'], 'upload-once/complete');
            return jsonResponse({'recordingStatus': 'SUCCEEDED'}, 202);
          }
          return jsonResponse({
            ...detail(version: 4),
            'recordingFileId': 'file_rec_1',
            'recordingStatus': 'SUCCEEDED',
          });
        }),
      )..setServiceSession('token');
      final result = await api.call('receptions/recording-upload', {
        'id': 'rec_1',
        'expectedVersion': 2,
        'requestId': 'upload-once',
        'originalFilename': 'rec_표시명_01012345678@261120_100000.wav',
        'bytes': bytes,
      });
      expect(paths, [
        '/api/v1/receptions/rec_1/recordings/upload-requests',
        '/object',
        '/api/v1/receptions/rec_1/recordings/file_rec_1/completion',
        '/api/v1/receptions/rec_1',
      ]);
      expect(result['consultation'].containsKey('uploadUrl'), false);
      api.close();
    },
  );

  test(
    'missing service auth and unsafe IDs never make a network request',
    () async {
      var calls = 0;
      final api = SpringFrontendBackend(
        'https://api.example.com',
        client: MockClient((r) async {
          calls++;
          return jsonResponse({});
        }),
      );
      await expectLater(
        api.call('receptions/list'),
        throwsA(isA<ServiceFailure>().having((e) => e.status, 'status', 401)),
      );
      api.setServiceSession('token');
      await expectLater(
        api.call('receptions/detail', {'id': '../other'}),
        throwsA(isA<ServiceFailure>()),
      );
      expect(calls, 0);
      api.close();
    },
  );

  test('logout discards an in-flight response', () async {
    final gate = Completer<http.Response>();
    final api = SpringFrontendBackend(
      'https://api.example.com',
      client: MockClient((r) => gate.future),
    )..setServiceSession('old-session');
    final pending = api.call('receptions/detail', {'id': 'rec_1'});
    final assertion = expectLater(
      pending,
      throwsA(isA<ServiceFailure>().having((e) => e.status, 'status', 499)),
    );
    api.clearSession();
    gate.complete(jsonResponse(detail()));
    await assertion;
    api.close();
  });

  test(
    'lost completion response retries completion without a new reservation or PUT',
    () async {
      final bytes = Uint8List.fromList([82, 73, 70, 70]);
      final digest = base64Encode(sha256.convert(bytes).bytes);
      var reservations = 0, puts = 0, completions = 0;
      final api = SpringFrontendBackend(
        'https://api.example.com',
        client: MockClient((r) async {
          if (r.url.path.endsWith('/upload-requests')) {
            reservations++;
            return jsonResponse({
              'recordingFileId': 'file_rec_1',
              'version': 2,
              'method': 'PUT',
              'uploadUrl': 'https://storage.example.com/object',
              'requiredHeaders': {
                'Content-Length': '${bytes.length}',
                'x-amz-checksum-sha256': digest,
              },
            });
          }
          if (r.method == 'PUT') {
            puts++;
            return http.Response('', 200);
          }
          if (r.url.path.endsWith('/completion')) {
            completions++;
            expect(r.headers['Idempotency-Key'], 'same-upload/complete');
            if (completions == 1) throw http.ClientException('response lost');
            return jsonResponse({}, 202);
          }
          return jsonResponse(detail(version: 3));
        }),
      )..setServiceSession('token');
      final payload = {
        'id': 'rec_1',
        'expectedVersion': 1,
        'requestId': 'same-upload',
        'originalFilename': 'recording.wav',
        'bytes': bytes,
      };
      await expectLater(
        api.call('receptions/recording-upload', payload),
        throwsA(isA<ServiceFailure>()),
      );
      await api.call('receptions/recording-upload', {
        ...payload,
        'requestId': 'new-ui-attempt',
      });
      expect([reservations, puts, completions], [1, 1, 2]);
      api.close();
    },
  );

  test(
    'pending recording renews URL and rejects a different file before PUT',
    () async {
      var puts = 0;
      final api = SpringFrontendBackend(
        'https://api.example.com',
        client: MockClient((r) async {
          if (r.method == 'PUT') {
            puts++;
            return http.Response('', 200);
          }
          expect(
            r.url.path,
            '/api/v1/receptions/rec_1/recordings/file_rec_1/upload-requests',
          );
          expect(r.body, isEmpty);
          return jsonResponse({
            'recordingFileId': 'file_rec_1',
            'version': 2,
            'method': 'PUT',
            'uploadUrl': 'https://storage.example.com/object',
            'requiredHeaders': {
              'Content-Length': '999',
              'x-amz-checksum-sha256': 'different',
            },
          });
        }),
      )..setServiceSession('token');
      await expectLater(
        api.call('receptions/recording-upload', {
          'id': 'rec_1',
          'recordingFileId': 'file_rec_1',
          'expectedVersion': 2,
          'requestId': 'renew',
          'originalFilename': 'recording.wav',
          'bytes': Uint8List.fromList([1]),
        }),
        throwsA(isA<ServiceFailure>().having((e) => e.status, 'status', 400)),
      );
      expect(puts, 0);
      api.close();
    },
  );

  testWidgets('authenticated service session opens server-backed receptions', (
    tester,
  ) async {
    final api = SpringFrontendBackend(
      'https://api.example.com',
      client: MockClient((r) async {
        if (r.url.path == '/api/v1/receptions') {
          return jsonResponse({
            'items': [detail()],
            'page': {'page': 1, 'totalPages': 1},
          });
        }
        return jsonResponse(detail());
      }),
    )..setServiceSession('service-token');
    await tester.pumpWidget(
      AllcheckApp(
        role: AppRole.admin,
        store: AppStore(AppRole.admin),
        backend: api,
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('rec_1'), findsOneWidget);
    await tester.tap(find.text('rec_1'));
    await tester.pumpAndSettle();
    expect(find.text('상담 내용'), findsOneWidget);
    expect(find.text('보관된 상담 원문'), findsOneWidget);
    expect(find.text('고객명 *'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    api.close();
  });
}
