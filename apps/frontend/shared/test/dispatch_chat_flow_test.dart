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

Map<String, dynamic> registration(String status) => {
  'driverId': 101,
  'accountStatus': status,
  'version': 2,
  'submittedAt': '2026-11-20T01:00:00Z',
  'rejection': null,
  'profile': {
    'workerName': '테스트 기사',
    'phone': '01000000000',
    'email': 'driver@example.com',
    'residence': '테스트 주소',
    'vehicleNumber': '00가0000',
    'settlementAccount': {
      'bankName': '테스트은행',
      'accountNumber': '1234567890',
      'holderName': '테스트 기사',
    },
    'taxInvoiceEmail': 'tax@example.com',
    'specialties': ['BLOCKAGE'],
    'activityRegions': ['서울 강남구'],
    'equipment': ['내시경 카메라'],
  },
  'business': {
    'businessName': '테스트 설비',
    'businessRegistrationNumber': '1234567890',
    'openedOn': '2020-01-15',
    'representativeName': '테스트 대표',
    'companyPhone': '0200000000',
    'businessType': '서비스',
    'businessItem': '설비',
    'companyAddress': '테스트 사업장',
  },
  'businessVerification': {
    'status': 'VERIFIED',
    'verifiedAt': '2026-11-20T01:00:00Z',
  },
};

http.Response kakaoTokens() => jsonResponse({
  'accessToken': 'driver-token',
  'refreshToken': 'refresh',
  'user': {'accountId': 11, 'userType': 'DRIVER'},
});

http.Response adminTokens() => jsonResponse({
  'accessToken': 'admin-token',
  'refreshToken': 'refresh',
  'user': {'accountId': 1, 'userType': 'ADMIN', 'isBootstrapAdmin': true},
});

Map<String, dynamic> work({String? phone = '010-1234-5678'}) => {
  'workId': 'wrk_1',
  'requestId': 'req_1',
  'driverId': '101',
  'previousWorkId': null,
  'workStatus': 'NOT_STARTED',
  'version': 1,
  'workType': 'BLOCKAGE',
  'symptoms': '싱크대 막힘',
  'address': '서울 강남구 테스트로 1',
  'contactPhone': phone,
  'desiredVisitFrom': '2026-11-28T10:00:00+09:00',
  'desiredVisitTo': '2026-11-28T12:00:00+09:00',
  'notes': '방문 전 연락',
  'assignedAt': '2026-11-27T01:00:00Z',
  'completedAt': null,
  'cancelledAt': null,
  'reportId': null,
  'cancellation': null,
  'events': [],
  'canTransition': true,
  'canSubmitReport': false,
};

class DeviceLogin implements DriverAuth {
  @override
  Future<KakaoIdentity?> signIn() async =>
      const KakaoIdentity(userId: 'test', accessToken: 'provider-token');
  @override
  Future<void> signOut() async {}
}

Future<void> tapText(WidgetTester tester, String text) async {
  FocusManager.instance.primaryFocus?.unfocus();
  await tester.pumpAndSettle();
  await Scrollable.ensureVisible(
    tester.element(find.text(text).first),
    alignment: 0.5,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text(text).first);
  await tester.pumpAndSettle();
}

Future<void> signInDriver(WidgetTester tester, FrontendBackend backend) async {
  await tester.pumpWidget(
    AllcheckApp(
      role: AppRole.driver,
      store: AppStore(AppRole.driver),
      backend: backend,
      driverAuth: DeviceLogin(),
    ),
  );
  await tapText(tester, '카카오 로그인');
  await tapText(tester, '계속');
}

Future<void> signInAdmin(WidgetTester tester, FrontendBackend backend) async {
  await tester.pumpWidget(
    AllcheckApp(
      role: AppRole.admin,
      store: AppStore(AppRole.admin),
      backend: backend,
    ),
  );
  await tester.enterText(find.byType(TextFormField).at(0), 'admin');
  await tester.enterText(find.byType(TextFormField).at(1), 'test');
  await tester.ensureVisible(find.widgetWithText(FilledButton, '로그인'));
  await tester.tap(find.widgetWithText(FilledButton, '로그인'));
  await tester.pumpAndSettle();
}

void main() {
  group('approved driver becomes dispatchable', () {
    test('availability and location use the dispatch contracts', () async {
      var ready = false;
      final backend = DriverOnboardingBackend(
        'https://api.example.com',
        client: MockClient((request) async {
          switch (request.url.path) {
            case '/api/v1/auth/sessions/kakao':
              return kakaoTokens();
            case '/api/v1/drivers/me/registration':
              return jsonResponse(registration('ACTIVATED'));
            case '/api/v1/drivers/me/availability':
              if (request.method == 'PATCH') {
                expect(jsonDecode(request.body), {'availability': 'READY'});
                ready = true;
              } else {
                expect(request.method, 'GET');
              }
              return jsonResponse({
                'availability': ready ? 'READY' : 'UNAVAILABLE',
                'accountStatus': 'ACTIVATED',
                'updatedAt': '2026-11-27T01:00:00Z',
              });
            case '/api/v1/drivers/me/location':
              expect(request.method, 'PUT');
              expect(jsonDecode(request.body), {
                'latitude': 37.5,
                'longitude': 127.0,
                'accuracyMeters': 12.0,
                'measuredAt': '2026-11-27T01:00:00.000Z',
              });
              return jsonResponse({
                'locationUsability': 'VALID',
                'lastUpdatedAt': '2026-11-27T01:00:00Z',
                'issue': null,
              });
          }
          throw StateError('Unexpected ${request.method} ${request.url}');
        }),
      );
      addTearDown(backend.close);
      final login = await backend.call('auth/kakao', {'accessToken': 'kakao'});
      expect(login['state']['availability'], '휴식');
      final changed = await backend.call('drivers/availability', {
        'availability': '대기',
        'requestId': 'not-sent',
      });
      expect(changed['state']['availability'], '대기');
      await backend.call('drivers/location', {
        'latitude': 37.5,
        'longitude': 127.0,
        'accuracy': 12.0,
        'recordedAt': '2026-11-27T10:00:00+09:00',
      });
      // A periodic state refresh keeps the server-owned availability/location.
      final refreshed = await backend.call('workflow/state');
      expect(refreshed['state']['availability'], '대기');
      expect(refreshed['state']['location'], startsWith('갱신됨'));
    });

    test('a pending driver does not query dispatch availability', () async {
      final paths = <String>[];
      final backend = DriverOnboardingBackend(
        'https://api.example.com',
        client: MockClient((request) async {
          paths.add(request.url.path);
          if (request.url.path.endsWith('/sessions/kakao')) return kakaoTokens();
          return jsonResponse(registration('PENDING_APPROVAL'));
        }),
      );
      addTearDown(backend.close);
      await backend.call('auth/kakao', {'accessToken': 'kakao'});
      expect(paths, isNot(contains('/api/v1/drivers/me/availability')));
    });

    testWidgets('approved home shows availability and location controls', (
      tester,
    ) async {
      final backend = DriverOnboardingBackend(
        'https://api.example.com',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/kakao')) return kakaoTokens();
          if (request.url.path.endsWith('/me/availability')) {
            return jsonResponse({
              'availability': 'UNAVAILABLE',
              'accountStatus': 'ACTIVATED',
              'updatedAt': '2026-11-27T01:00:00Z',
            });
          }
          return jsonResponse(registration('ACTIVATED'));
        }),
      );
      addTearDown(backend.close);
      await signInDriver(tester, backend);
      expect(find.text('테스트 기사님, 안녕하세요'), findsOneWidget);
      expect(find.text('작업 가능 상태'), findsOneWidget);
      expect(find.widgetWithText(ChoiceChip, '대기'), findsOneWidget);
      expect(find.text('현재 위치'), findsOneWidget);
      expect(find.text('대기 중 위치 갱신 켜기'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
    });
  });

  testWidgets('accepted work opens a detail with contact and visit window', (
    tester,
  ) async {
    final paths = <String>[];
    final backend = DriverOnboardingBackend(
      'https://api.example.com',
      client: MockClient((request) async {
        paths.add(request.url.path);
        switch (request.url.path) {
          case '/api/v1/auth/sessions/kakao':
            return kakaoTokens();
          case '/api/v1/drivers/me/availability':
            return jsonResponse({
              'availability': 'UNAVAILABLE',
              'accountStatus': 'ACTIVATED',
              'updatedAt': '2026-11-27T01:00:00Z',
            });
          case '/api/v1/drivers/me/works':
            return jsonResponse({
              'items': [work()],
              'page': 1,
              'size': 20,
              'hasNext': false,
            });
          case '/api/v1/works/wrk_1':
            return jsonResponse(work());
        }
        return jsonResponse(registration('ACTIVATED'));
      }),
    );
    addTearDown(backend.close);
    await signInDriver(tester, backend);
    await tapText(tester, '내 작업');
    expect(find.text('서울 강남구 테스트로 1'), findsOneWidget);
    await tapText(tester, '서울 강남구 테스트로 1');
    expect(paths, contains('/api/v1/works/wrk_1'));
    expect(find.text('010-1234-5678'), findsOneWidget);
    expect(find.text('2026-11-28 10:00 ~ 12:00'), findsOneWidget);
    expect(find.text('방문 전 연락'), findsOneWidget);
    expect(find.byTooltip('연락처 복사'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('work list reloads after statistics and on app bar refresh', (
    tester,
  ) async {
    var requestLoads = 0;
    final backend = AdminRestBackend(
      'https://api.example.com',
      receptionsFactory: SpringFrontendBackend.withSession,
      client: MockClient((request) async {
        switch (request.url.path) {
          case '/api/v1/auth/sessions/admin':
            return adminTokens();
          case '/api/v1/work-statistics':
            return jsonResponse({
              'metrics': {
                'totalRequests': 1,
                'openRequests': 1,
                'completedRequests': 0,
                'cancelledRequests': 0,
                'totalCostKrw': 0,
              },
            });
          case '/api/v1/service-requests':
            requestLoads++;
            return jsonResponse({
              'items': [
                {
                  'requestId': 'req_1',
                  'address': '서울 강남구 테스트로 1',
                  'symptoms': '싱크대 막힘',
                  'requestStatus': 'DISPATCHING',
                },
              ],
              'page': 1,
              'size': 20,
              'hasNext': false,
            });
          case '/api/v1/drivers':
            return jsonResponse({
              'items': [],
              'page': {'totalPages': 0},
            });
        }
        if (request.url.path.endsWith('/accounts/me')) {
          return jsonResponse({'accountId': 1, 'userType': 'ADMIN'});
        }
        throw StateError('Unexpected ${request.method} ${request.url}');
      }),
    );
    addTearDown(backend.close);
    await signInAdmin(tester, backend);
    await tapText(tester, '최근 30일 작업 통계');
    expect(find.text('최근 30일 작업 통계 · 한국시간'), findsOneWidget);
    await tester.tap(find.text('작업목록').last);
    await tester.pumpAndSettle();
    expect(find.text('서울 강남구 테스트로 1'), findsOneWidget);
    expect(find.text('조회 결과가 없습니다.'), findsNothing);
    expect(requestLoads, 1);
    await tester.tap(find.byTooltip('새로고침'));
    await tester.pumpAndSettle();
    expect(requestLoads, 2);
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('admin starts a chat from driver detail and marks it read', (
    tester,
  ) async {
    final reads = <Object?>[];
    final summary = {...registration('ACTIVATED'), 'workerName': '테스트 기사'}
      ..remove('profile')
      ..remove('business');
    final backend = AdminRestBackend(
      'https://api.example.com',
      receptionsFactory: SpringFrontendBackend.withSession,
      client: MockClient((request) async {
        switch (request.url.path) {
          case '/api/v1/auth/sessions/admin':
            return adminTokens();
          case '/api/v1/drivers':
            return jsonResponse({
              'items': [summary],
              'page': {'page': 1, 'size': 20, 'totalElements': 1, 'totalPages': 1},
            });
          case '/api/v1/drivers/101':
            return jsonResponse(registration('ACTIVATED'));
          case '/api/v1/chat-rooms/101/messages':
            return jsonResponse({
              'items': [
                {
                  'messageId': 'msg_2',
                  'driverId': 101,
                  'sender': 'DRIVER',
                  'body': '도착 예정입니다',
                  'sentAt': '2026-11-27T01:01:00Z',
                  'readAt': null,
                },
                {
                  'messageId': 'msg_1',
                  'driverId': 101,
                  'sender': 'ADMIN',
                  'body': '확인 부탁드립니다',
                  'sentAt': '2026-11-27T01:00:00Z',
                  'readAt': null,
                },
              ],
              'nextCursor': null,
              'readOnly': false,
            });
          case '/api/v1/chat-rooms/101/read':
            expect(request.method, 'PUT');
            reads.add(jsonDecode(request.body));
            return jsonResponse({
              'lastReadMessageId': 'msg_2',
              'readAt': '2026-11-27T01:02:00Z',
            });
        }
        if (request.url.path.endsWith('/accounts/me')) {
          return jsonResponse({'accountId': 1, 'userType': 'ADMIN'});
        }
        throw StateError('Unexpected ${request.method} ${request.url}');
      }),
    );
    addTearDown(backend.close);
    await signInAdmin(tester, backend);
    await tapText(tester, '기사 가입 심사');
    await tapText(tester, '테스트 기사');
    await tapText(tester, '기사와 대화하기');
    expect(find.text('도착 예정입니다'), findsOneWidget);
    expect(reads, [
      {'lastReadMessageId': 'msg_2'},
    ]);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
