import 'dart:convert';
import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  test(
    'consultation and dispatch use the authenticated administrator session',
    () async {
      final backend = AdminRestBackend(
        'https://example.com/api/v1',
        client: MockClient((request) async {
          final path = request.url.path;
          if (path.endsWith('/sessions/admin')) {
            return http.Response(
              jsonEncode({
                'accessToken': 'admin-token',
                'refreshToken': 'refresh',
                'user': {'accountId': 42, 'userType': 'ADMIN'},
              }),
              200,
            );
          }
          expect(request.headers['Authorization'], 'Bearer admin-token');
          if (path == '/api/v1/service-requests') {
            expect(request.headers['Idempotency-Key'], 'register-sample');
            expect(jsonDecode(request.body), {
              'receptionId': 'rcp_1',
              'expectedVersion': 2,
            });
            return http.Response('{"resourceId":"req_1"}', 201);
          }
          expect(path, '/api/v1/receptions/rcp_1');
          return http.Response(
            jsonEncode({
              'receptionId': 'rcp_1',
              'requestId': 'req_1',
              'reviewStatus': 'CONFIRMED',
              'transcript': '상담 원문',
              'aiOriginal': {'symptoms': '누수'},
            }),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        }),
      );
      addTearDown(backend.close);
      await expectLater(
        backend.call('receptions/detail', {'id': 'rcp_1'}),
        throwsA(
          isA<ServiceFailure>().having((error) => error.status, 'status', 401),
        ),
      );
      await backend.call('auth/admin-login', {
        'username': 'admin',
        'password': 'test',
      });
      expect(backend.recordingOwnerKey, 'https://example.com|42');
      final result = await backend.call('receptions/register', {
        'id': 'rcp_1',
        'expectedVersion': 2,
        'requestId': 'register-sample',
      });
      expect(result['resourceId'], 'req_1');
      expect(result['consultation']['stt'], '상담 원문');
      expect(result['consultation']['analysis'], {'symptoms': '누수'});
      backend.clearSession();
      expect(backend.recordingOwnerKey, isNull);
    },
  );

  test(
    'admin login uses the REST route and preserves credentials exactly',
    () async {
      final requests = <http.Request>[];
      final backend = AdminRestBackend(
        'https://example.com/api/v1',
        client: MockClient((request) async {
          requests.add(request);
          if (requests.length == 1) {
            expect(request.url.path, '/api/v1/auth/sessions/admin');
            expect(jsonDecode(request.body), {
              'loginId': ' Admin ',
              'password': ' secret ',
            });
            return http.Response(
              jsonEncode({
                'accessToken': 'access',
                'refreshToken': 'refresh',
                'user': {
                  'accountId': 1,
                  'userType': 'ADMIN',
                  'isBootstrapAdmin': true,
                },
              }),
              200,
            );
          }
          expect(request.headers['Authorization'], 'Bearer access');
          if (request.url.path.endsWith('/logout')) {
            expect(jsonDecode(request.body), {'refreshToken': 'refresh'});
            return http.Response('', 204);
          }
          expect(request.url.path, '/api/v1/admin-accounts');
          expect(jsonDecode(request.body), {
            'loginId': 'child',
            'initialPassword': 'initial',
          });
          return http.Response('{}', 201);
        }),
      );
      final result = await backend.call('auth/admin-login', {
        'username': ' Admin ',
        'password': ' secret ',
      });
      expect((result['state'] as Map)['account']['isBootstrapAdmin'], true);
      await backend.call('admin-accounts/create', {
        'loginId': 'child',
        'password': 'initial',
      });
      await backend.call('auth/logout');
      backend.close();
    },
  );

  test(
    'driver review uses server version and replays the same idempotency key after refresh',
    () async {
      var approvalCalls = 0;
      final backend = AdminRestBackend(
        'https://example.com/api/v1',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/admin'))
            return http.Response(
              jsonEncode({
                'accessToken': 'old',
                'refreshToken': 'refresh',
                'user': {'userType': 'ADMIN'},
              }),
              200,
            );
          if (request.url.path.endsWith('/tokens/refresh'))
            return http.Response(
              '{"accessToken":"new","refreshToken":"replacement"}',
              200,
            );
          if (request.url.path.endsWith('/approval')) {
            approvalCalls++;
            expect(request.headers['Idempotency-Key'], 'stable-key');
            expect(jsonDecode(request.body), {
              'expectedVersion': 7,
              'decision': 'APPROVE',
            });
            if (approvalCalls == 1) return http.Response('', 401);
            expect(request.headers['Authorization'], 'Bearer new');
            return http.Response('{}', 200);
          }
          expect(request.method, 'GET');
          expect(request.url.path, '/api/v1/drivers/2');
          return http.Response(
            jsonEncode({
              'driverId': 2,
              'accountStatus': 'ACTIVATED',
              'version': 8,
              'profile': {'workerName': '기사'},
              'business': {},
            }),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        }),
      );
      await backend.call('auth/admin-login', {
        'username': 'admin',
        'password': 'secret',
      });
      final result = await backend.call('drivers/decide', {
        'id': '2',
        'approve': true,
        'expectedVersion': 7,
        'requestId': 'stable-key',
      });
      expect(approvalCalls, 2);
      expect(
        ((result['state'] as Map)['drivers'] as List).single['status'],
        '활성',
      );
      backend.close();
    },
  );

  test('deactivation and password reset use versioned contracts', () async {
    final backend = AdminRestBackend(
      'https://example.com/api/v1',
      client: MockClient((request) async {
        if (request.method == 'DELETE') {
          expect(request.url.queryParameters['expectedVersion'], '3');
          return http.Response('', 204);
        }
        expect(request.method, 'PUT');
        expect(jsonDecode(request.body), {
          'expectedVersion': 3,
          'newPassword': 'reset',
        });
        return http.Response('{}', 200);
      }),
    );
    await backend.call('admin-accounts/update', {
      'accountId': 2,
      'expectedVersion': 3,
      'password': 'reset',
    });
    await backend.call('admin-accounts/deactivate', {
      'accountId': 2,
      'expectedVersion': 3,
    });
    backend.close();
  });
}
