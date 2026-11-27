import 'dart:async';
import 'dart:convert';

import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

http.Response jsonResponse(Object body, [int status = 200]) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

const adminUser = {
  'accountId': 1,
  'userType': 'ADMIN',
  'isBootstrapAdmin': true,
};

http.Response login([String token = 'old']) => jsonResponse({
  'accessToken': token,
  'refreshToken': 'refresh-$token',
  'user': adminUser,
});

http.Response receptions() => jsonResponse({
  'items': [
    {
      'receptionId': 'rec_1',
      'version': 1,
      'reviewStatus': 'PENDING',
      'recordingStatus': 'NOT_AVAILABLE',
      'reviewEditable': false,
      'finalData': <String, dynamic>{},
    },
  ],
  'page': {'page': 1, 'totalPages': 1},
});

AdminRestBackend administrator(http.Client client) => AdminRestBackend(
  'https://api.example.com',
  client: client,
  receptionsFactory: SpringFrontendBackend.withSession,
);

void main() {
  test(
    'admin login, receptions and account reads share the renewed session',
    () async {
      var refreshes = 0;
      final backend = administrator(
        MockClient((request) async {
          switch (request.url.path) {
            case '/api/v1/auth/sessions/admin':
              return login();
            case '/api/v1/auth/tokens/refresh':
              refreshes++;
              expect(jsonDecode(request.body), {'refreshToken': 'refresh-old'});
              return login('new');
            case '/api/v1/receptions':
              if (request.headers['Authorization'] == 'Bearer old') {
                return jsonResponse({}, 401);
              }
              expect(request.headers['Authorization'], 'Bearer new');
              return receptions();
            case '/api/v1/auth/accounts/me':
              expect(request.headers['Authorization'], 'Bearer new');
              return jsonResponse(adminUser);
            case '/api/v1/drivers':
              expect(request.headers['Authorization'], 'Bearer new');
              return jsonResponse({
                'items': [],
                'page': {'totalPages': 0},
              });
            case '/api/v1/auth/sessions/logout':
              expect(jsonDecode(request.body), {'refreshToken': 'refresh-new'});
              return http.Response('', 204);
            default:
              throw StateError('Unexpected route ${request.url.path}');
          }
        }),
      );
      addTearDown(backend.close);
      await backend.call('auth/admin-login', {
        'username': 'admin',
        'password': 'test',
      });
      final result = await backend.call('receptions/list');
      expect((result['consultations'] as List).single['id'], 'rec_1');
      await backend.call('drivers/list');
      expect(refreshes, 1);
      await backend.call('auth/logout');
      await expectLater(
        backend.call('receptions/list'),
        throwsA(
          isA<ServiceFailure>().having((e) => e.status, 'logged out', 401),
        ),
      );
    },
  );

  test(
    'protected auth account reads renew once and preserve the request',
    () async {
      var reads = 0;
      final session = HttpFrontendBackend(
        'https://api.example.com',
        restAuth: true,
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/admin')) return login();
          if (request.url.path.endsWith('/tokens/refresh')) return login('new');
          reads++;
          expect(request.method, 'GET');
          expect(request.url.path, '/api/v1/auth/accounts/me');
          if (reads == 1) return jsonResponse({}, 401);
          expect(request.headers['Authorization'], 'Bearer new');
          return jsonResponse(adminUser);
        }),
      );
      addTearDown(session.close);
      await session.call('auth/admin-login');
      await session.requestJson(
        'auth/me',
        {},
        path: 'auth/accounts/me',
        method: 'GET',
      );
      expect(reads, 2);
    },
  );

  test(
    'parallel expired requests share one refresh and keep their command keys',
    () async {
      final refreshing = Completer<void>();
      final refreshResult = Completer<http.Response>();
      var refreshes = 0;
      final session = HttpFrontendBackend(
        'https://api.example.com',
        restAuth: true,
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/admin')) return login();
          if (request.url.path.endsWith('/tokens/refresh')) {
            refreshes++;
            refreshing.complete();
            return refreshResult.future;
          }
          expect(request.method, 'POST');
          expect(
            request.headers['Idempotency-Key'],
            request.url.path.split('/').last,
          );
          expect(jsonDecode(request.body), {'expectedVersion': 3});
          if (request.headers['Authorization'] == 'Bearer old')
            return jsonResponse({}, 401);
          expect(request.headers['Authorization'], 'Bearer new');
          return jsonResponse({});
        }),
      );
      addTearDown(session.close);
      await session.call('auth/admin-login');
      final pending = Future.wait([
        for (final key in ['first', 'second'])
          session.requestJson(
            'resource/update',
            {'expectedVersion': 3},
            path: 'resources/$key',
            method: 'POST',
            headers: {'Idempotency-Key': key},
          ),
      ]);
      await refreshing.future;
      refreshResult.complete(login('new'));
      await pending;
      expect(refreshes, 1);
    },
  );

  test(
    'a late 401 reuses the current bearer instead of refreshing again',
    () async {
      final lateResponse = Completer<http.Response>();
      final lateStarted = Completer<void>();
      var refreshes = 0;
      final session = HttpFrontendBackend(
        'https://api.example.com',
        restAuth: true,
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/admin')) return login();
          if (request.url.path.endsWith('/tokens/refresh')) {
            refreshes++;
            return login('new');
          }
          if (request.headers['Authorization'] == 'Bearer old') {
            if (request.url.path.endsWith('/late')) {
              lateStarted.complete();
              return lateResponse.future;
            }
            return jsonResponse({}, 401);
          }
          expect(request.headers['Authorization'], 'Bearer new');
          return jsonResponse({});
        }),
      );
      addTearDown(session.close);
      await session.call('auth/admin-login');
      final late = session.call('resources/late');
      await lateStarted.future;
      await session.call('resources/first');
      lateResponse.complete(jsonResponse({}, 401));
      await late;
      expect(refreshes, 1);
    },
  );

  test(
    'an old refresh cannot restore a logged-out or replaced session',
    () async {
      final refreshing = Completer<void>();
      final refreshResult = Completer<http.Response>();
      var logins = 0;
      final session = HttpFrontendBackend(
        'https://api.example.com',
        restAuth: true,
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/admin'))
            return login(++logins == 1 ? 'old' : 'replacement');
          if (request.url.path.endsWith('/tokens/refresh')) {
            refreshing.complete();
            return refreshResult.future;
          }
          if (request.headers['Authorization'] == 'Bearer old')
            return jsonResponse({}, 401);
          expect(request.headers['Authorization'], 'Bearer replacement');
          return jsonResponse({});
        }),
      );
      addTearDown(session.close);
      await session.call('auth/admin-login');
      final obsolete = expectLater(
        session.call('resources/detail'),
        throwsA(
          isA<ServiceFailure>().having(
            (e) => e.status,
            'obsolete session',
            499,
          ),
        ),
      );
      await refreshing.future;
      session.clearSession();
      await session.call('auth/admin-login');
      refreshResult.complete(login('stale'));
      await obsolete;
      await session.call('resources/detail');
    },
  );

  test('refresh rejection clears the session without looping', () async {
    var refreshes = 0;
    final session = HttpFrontendBackend(
      'https://api.example.com',
      restAuth: true,
      client: MockClient((request) async {
        if (request.url.path.endsWith('/sessions/admin')) return login();
        if (request.url.path.endsWith('/tokens/refresh')) refreshes++;
        return jsonResponse({}, 401);
      }),
    );
    addTearDown(session.close);
    await session.call('auth/admin-login');
    await expectLater(
      session.call('resources/detail'),
      throwsA(
        isA<ServiceFailure>().having(
          (e) => e.status,
          'authentication failure',
          401,
        ),
      ),
    );
    expect(session.hasServiceSession, isFalse);
    expect(refreshes, 1);
  });

  testWidgets(
    'admin login opens receptions and keeps driver/account menus available',
    (tester) async {
      var expire = false;
      final store = AppStore(AppRole.admin);
      final backend = administrator(
        MockClient((request) async {
          if (request.url.path.endsWith('/sessions/admin'))
            return login('active');
          if (expire) return jsonResponse({}, 401);
          expect(request.headers['Authorization'], 'Bearer active');
          if (request.url.path == '/api/v1/receptions') return receptions();
          if (request.url.path.endsWith('/accounts/me'))
            return jsonResponse(adminUser);
          if (request.url.path == '/api/v1/drivers') {
            return jsonResponse({
              'items': [],
              'page': {'totalPages': 0},
            });
          }
          throw StateError('Unexpected route ${request.url.path}');
        }),
      );
      addTearDown(backend.close);
      await tester.pumpWidget(
        AllcheckApp(role: AppRole.admin, store: store, backend: backend),
      );
      await tester.enterText(find.byType(TextFormField).at(0), 'admin');
      await tester.enterText(find.byType(TextFormField).at(1), 'test');
      await tester.ensureVisible(find.widgetWithText(FilledButton, '로그인'));
      await tester.tap(find.widgetWithText(FilledButton, '로그인'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(FilledButton, '관리자 계정 관리'), findsOneWidget);
      await tester.tap(find.text('상담 접수 · STT / AI 분석'));
      await tester.pumpAndSettle();
      expect(find.text('rec_1'), findsOneWidget);
      expect(find.byType(NavigationBar), findsOneWidget);
      await tester.tap(find.byIcon(Icons.chevron_left));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, '기사 가입 심사'));
      await tester.pumpAndSettle();
      expect(store.consultations.single['id'], 'rec_1');
      expire = true;
      await tester.tap(find.byTooltip('새로고침'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(FilledButton, '로그인'), findsOneWidget);
    expire = false;
    await tester.enterText(find.byType(TextFormField).at(0), 'another-admin');
    await tester.enterText(find.byType(TextFormField).at(1), 'test');
    await tester.ensureVisible(find.widgetWithText(FilledButton, '로그인'));
    await tester.tap(find.widgetWithText(FilledButton, '로그인'));
    await tester.pumpAndSettle();
    expect(store.consultations, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
