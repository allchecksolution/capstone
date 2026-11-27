import 'dart:convert';

import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

// Response fields follow the existing DriverRegistrationView/Result,
// DriverSummary/DetailResponse and ReceptionDetailResponse DTOs.
http.Response jsonResponse(Object body, [int status = 200]) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);

const registrationForm = <String, dynamic>{
  'name': '테스트 기사',
  'phone': '01000000000',
  'email': 'driver@example.com',
  'address': '테스트 주소',
  'vehicle': '00가0000',
  'bank': '테스트은행',
  'bankAccount': '1234567890',
  'bankOwner': '테스트 기사',
  'taxEmail': 'tax@example.com',
  'specialties': ['막힘', '방수'],
  'regions': ['서울 강남구'],
  'equipment': ['내시경 카메라'],
  'company': '테스트 설비',
  'businessNumber': '1234567890',
  'openedOn': '2020-01-15',
  'owner': '테스트 대표',
  'companyPhone': '0200000000',
  'businessType': '서비스',
  'businessItem': '설비',
  'companyAddress': '테스트 사업장',
};

Map<String, dynamic> registrationView(String status, int version) => {
  'driverId': 101,
  'accountStatus': status,
  'version': version,
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
    'specialties': ['BLOCKAGE', 'WATERPROOFING'],
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

class DeviceLogin implements DriverAuth {
  @override
  Future<KakaoIdentity?> signIn() async =>
      const KakaoIdentity(userId: 'test', accessToken: 'provider-token');
  @override
  Future<void> signOut() async {}
}

Future<void> tapText(WidgetTester tester, String text) async {
  FocusManager.instance.primaryFocus?.unfocus();
  // A previous result snackbar may cover buttons at the bottom of the page.
  for (final messenger in tester.stateList<ScaffoldMessengerState>(
    find.byType(ScaffoldMessenger),
  )) {
    messenger.removeCurrentSnackBar();
  }
  await tester.pumpAndSettle();
  await Scrollable.ensureVisible(
    tester.element(find.text(text)),
    alignment: 0.5,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text(text));
  await tester.pumpAndSettle();
}

void main() {
  test(
    'admin detail shows the latest rejection only while the application is rejected',
    () async {
      var status = 'REJECTED';
      final backend = AdminRestBackend(
        'https://api.example.com',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/admin'))
            return jsonResponse({
              'accessToken': 'admin-token',
              'refreshToken': 'refresh',
              'user': {'accountId': 1, 'userType': 'ADMIN'},
            });
          expect(request.url.path, '/api/v1/drivers/101');
          expect(request.headers['Authorization'], 'Bearer admin-token');
          return jsonResponse({
            ...registrationView(status, 4),
            'rejectionHistory': [
              {'reason': '이전 반려 사유', 'rejectedAt': '2026-11-20T01:00:00Z'},
              {'reason': '현재 반려 사유', 'rejectedAt': '2026-11-21T01:00:00Z'},
            ],
          });
        }),
      );
      addTearDown(backend.close);
      await backend.call('auth/admin-login', {
        'username': 'admin',
        'password': 'test',
      });
      final rejected = await backend.call('drivers/detail', {'id': '101'});
      expect(
        (rejected['state']['drivers'] as List).single['reason'],
        '현재 반려 사유',
      );
      status = 'PENDING_APPROVAL';
      final resubmitted = await backend.call('drivers/detail', {'id': '101'});
      expect(
        (resubmitted['state']['drivers'] as List).single['reason'],
        isNull,
      );
    },
  );

  test(
    'driver submits, admin rejects, driver resubmits and admin approves the server record',
    () async {
      Map<String, dynamic>? stored;
      final rejections = <Map<String, dynamic>>[];
      var submissions = 0;
      Future<http.Response> server(http.Request request) async {
        final path = request.url.path;
        if (path.endsWith('/sessions/kakao') ||
            path.endsWith('/sessions/admin')) {
          final administrator = path.endsWith('/admin');
          return jsonResponse({
            'accessToken': administrator ? 'admin-token' : 'driver-token',
            'refreshToken': 'refresh-token',
            'user': {
              'accountId': administrator ? 1 : 11,
              'userType': administrator ? 'ADMIN' : 'DRIVER',
            },
          });
        }
        final isAdmin =
            request.headers['Authorization'] == 'Bearer admin-token';
        final body = request.body.isEmpty
            ? <String, dynamic>{}
            : jsonDecode(request.body) as Map<String, dynamic>;
        if (path.endsWith('/business-verifications')) {
          expect(isAdmin, isFalse);
          expect(
            body.keys,
            unorderedEquals([
              'businessRegistrationNumber',
              'openedOn',
              'representativeName',
            ]),
          );
          return jsonResponse({
            'status': 'VERIFIED',
            'verifiedAt': '2026-11-20T01:00:00Z',
          });
        }
        if (path == '/api/v1/drivers/me/registration' &&
            request.method == 'GET') {
          expect(isAdmin, isFalse);
          return stored == null
              ? jsonResponse({'code': 'DRIVER_NOT_FOUND'}, 404)
              : jsonResponse(stored!);
        }
        if (path == '/api/v1/drivers/me/registration' ||
            path.endsWith('/registration/resubmission')) {
          expect(request.method, 'POST');
          submissions++;
          final again = path.endsWith('/resubmission');
          expect(
            request.headers['Idempotency-Key'],
            again ? 'resubmit-key' : 'submit-key',
          );
          if (again) expect(body['expectedVersion'], stored!['version']);
          final payload =
              (again ? body['registration'] : body) as Map<String, dynamic>;
          expect(payload.keys, unorderedEquals(['profile', 'business']));
          expect(payload['profile']['specialties'], [
            'BLOCKAGE',
            'WATERPROOFING',
          ]);
          stored = {
            ...registrationView('PENDING_APPROVAL', again ? 3 : 1),
            ...payload,
          };
          // The actual submission response contains metadata only, no profile.
          return jsonResponse({
            for (final key in [
              'driverId',
              'accountStatus',
              'version',
              'submittedAt',
            ])
              key: stored![key],
          }, again ? 200 : 201);
        }
        expect(isAdmin, isTrue);
        if (path.endsWith('/accounts/me'))
          return jsonResponse({'accountId': 1, 'userType': 'ADMIN'});
        if (path == '/api/v1/drivers')
          return jsonResponse({
            'items': [
              {...stored!, 'workerName': '테스트 기사', 'businessName': '테스트 설비'}
                ..remove('profile')
                ..remove('business'),
            ],
            'page': {
              'page': 1,
              'size': 20,
              'totalElements': 1,
              'totalPages': 1,
            },
          });
        if (path.endsWith('/101/approval')) {
          expect(request.method, 'POST');
          expect(body['expectedVersion'], stored!['version']);
          final approved = body['decision'] == 'APPROVE';
          stored!['accountStatus'] = approved ? 'ACTIVATED' : 'REJECTED';
          stored!['version'] = (stored!['version'] as int) + 1;
          stored!['rejection'] = approved
              ? null
              : {
                  'reason': body['rejectionReason'],
                  'rejectedAt': '2026-11-21T01:00:00Z',
                };
          if (!approved)
            rejections.add(
              Map<String, dynamic>.from(stored!['rejection'] as Map),
            );
          return jsonResponse({
            'driverId': 101,
            'accountStatus': stored!['accountStatus'],
            'version': stored!['version'],
            'decidedAt': '2026-11-21T01:00:00Z',
          });
        }
        if (path == '/api/v1/drivers/101')
          return jsonResponse({...stored!, 'rejectionHistory': rejections});
        throw StateError('Unexpected route $path');
      }

      final driver = DriverOnboardingBackend(
        'https://api.example.com',
        client: MockClient(server),
      );
      final admin = AdminRestBackend(
        'https://api.example.com',
        client: MockClient(server),
      );
      addTearDown(driver.close);
      addTearDown(admin.close);
      expect(
        (await driver.call('auth/kakao', {
          'accessToken': 'provider-token',
        }))['account'],
        isNull,
      );
      await driver.call('drivers/business-check', {
        'businessNumber': '1234567890',
        'openedOn': '2020-01-15',
        'owner': '테스트 대표',
      });
      final submitted = await driver.call('drivers/register', {
        'profile': registrationForm,
        'requestId': 'submit-key',
      });
      expect(submitted['state']['account']['status'], '승인 대기');
      await admin.call('auth/admin-login', {
        'username': 'admin',
        'password': 'test',
      });
      final listed = await admin.call('drivers/list');
      expect((listed['state']['drivers'] as List).single['name'], '테스트 기사');
      final detail = await admin.call('drivers/detail', {'id': '101'});
      expect((detail['state']['drivers'] as List).single['specialties'], [
        '막힘',
        '방수',
      ]);
      final rejected = await admin.call('drivers/decide', {
        'id': '101',
        'approve': false,
        'reason': '등록 정보 보완',
        'expectedVersion': 1,
        'requestId': 'reject-key',
      });
      expect(
        (rejected['state']['drivers'] as List).single['reason'],
        '등록 정보 보완',
      );
      final driverRejected = await driver.call('workflow/state');
      expect(driverRejected['state']['account']['status'], '반려');
      expect(driverRejected['state']['account']['reason'], '등록 정보 보완');
      await driver.call('drivers/register', {
        'profile': registrationForm,
        'requestId': 'resubmit-key',
      });
      await admin.call('drivers/detail', {'id': '101'});
      await admin.call('drivers/decide', {
        'id': '101',
        'approve': true,
        'expectedVersion': 3,
        'requestId': 'approve-key',
      });
      final activated = await driver.call('workflow/state');
      expect(activated['state']['account']['status'], '활성');
      expect(activated['state']['account']['version'], 4);
      expect(activated['state']['account']['reason'], isNull);
      expect(submissions, 2);
    },
  );

  testWidgets(
    'the pending approval view exposes a manual server status refresh',
    (tester) async {
      var status = 'PENDING_APPROVAL';
      final backend = DriverOnboardingBackend(
        'https://api.example.com',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/kakao'))
            return jsonResponse({
              'accessToken': 'driver-token',
              'refreshToken': 'refresh',
              'user': {'accountId': 11, 'userType': 'DRIVER'},
            });
          return jsonResponse(
            registrationView(status, status == 'ACTIVATED' ? 2 : 1),
          );
        }),
      );
      addTearDown(backend.close);
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
      expect(find.text('관리자 검토가 진행 중입니다.'), findsOneWidget);
      status = 'ACTIVATED';
      await tapText(tester, '가입 상태 새로고침');
      expect(find.text('테스트 기사님, 안녕하세요'), findsOneWidget);
      expect(find.text('관리자 검토가 진행 중입니다.'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  for (final conflict in [false, true]) {
    testWidgets(
      'reception edits never overwrite a newer server version (conflict=$conflict)',
      (tester) async {
        var version = 1;
        var editable = true;
        var address = '기존 방문 주소';
        final posts = <http.Request>[];
        final from = DateTime.now().toUtc().add(const Duration(days: 1));
        final to = from.add(const Duration(hours: 1));
        Map<String, dynamic> detail() => {
          'receptionId': 'rec_3',
          'version': version,
          'reviewStatus': editable ? 'DRAFT' : 'CONFIRMED',
          'recordingStatus': 'NOT_AVAILABLE',
          'analysisStatus': 'NOT_AVAILABLE',
          'reviewEditable': editable,
          'transcript': '실제 계약의 STT 필드',
          'aiOriginal': {'symptoms': '최초 분석 증상'},
          'finalData': {
            'workType': 'BLOCKAGE',
            'symptoms': '증상',
            'address': address,
            'contactPhone': '01000000000',
            'desiredVisitFrom': from.toIso8601String(),
            'desiredVisitTo': to.toIso8601String(),
          },
        };
        final backend = SpringFrontendBackend(
          'https://api.example.com',
          client: MockClient((request) async {
            if (request.url.path == '/api/v1/receptions')
              return jsonResponse({
                'items': [
                  {
                    'receptionId': 'rec_3',
                    'version': version,
                    'reviewStatus': 'DRAFT',
                    'recordingStatus': 'NOT_AVAILABLE',
                  },
                ],
                'page': {'page': 1, 'totalPages': 1},
              });
            if (request.method == 'GET') return jsonResponse(detail());
            posts.add(request);
            final body = jsonDecode(request.body) as Map<String, dynamic>;
            if (body['expectedVersion'] != version)
              return jsonResponse({'code': 'VERSION_CONFLICT'}, 409);
            version++;
            if (request.url.path.endsWith('/confirmation')) {
              address = body['finalData']['address'] as String;
              editable = false;
            } else {
              editable = true;
            }
            return jsonResponse({'receptionId': 'rec_3', 'version': version});
          }),
        )..setServiceSession('service-token');
        addTearDown(backend.close);
        await tester.pumpWidget(
          AllcheckApp(
            role: AppRole.admin,
            store: AppStore(AppRole.admin),
            backend: backend,
          ),
        );
        await tester.pumpAndSettle();
        await tapText(tester, 'rec_3');
        final addressField = find.byKey(
          const ValueKey('reception/rec_3/address'),
        );
        await tester.enterText(addressField, '작성 중인 내용');
        version = 2;
        address = '서버에서 바뀐 주소';
        if (conflict) {
          await tapText(tester, '검토 완료 · 최종 정보 저장');
          expect(jsonDecode(posts.single.body)['expectedVersion'], 1);
        } else {
          await tapText(tester, '상세 새로고침');
          expect(posts, isEmpty);
        }
        expect(
          tester.widget<TextFormField>(addressField).controller!.text,
          '작성 중인 내용',
        );
        expect(find.text('검토 완료 · 최종 정보 저장'), findsNothing);
        await tapText(tester, '입력값을 최신 내용으로 바꾸기');
        expect(
          tester.widget<TextFormField>(addressField).controller!.text,
          '서버에서 바뀐 주소',
        );
        await tapText(tester, '검토 완료 · 최종 정보 저장');
        expect(jsonDecode(posts.last.body)['expectedVersion'], 2);
        expect(
          jsonDecode(posts.last.body)['finalData']['address'],
          '서버에서 바뀐 주소',
        );
        if (conflict)
          expect(
            posts.last.headers['Idempotency-Key'],
            isNot(posts.first.headers['Idempotency-Key']),
          );
        // A conflict followed by a successful save queues two snackbars.
        for (var i = 0; i < (conflict ? 2 : 1); i++) {
          await tester.pump(const Duration(seconds: 5));
          await tester.pumpAndSettle();
        }
        await tapText(tester, '검토 확정 취소 · 다시 수정');
        await tapText(tester, '확인');
        expect(
          tester.widget<TextFormField>(addressField).controller!.text,
          '서버에서 바뀐 주소',
        );
        expect(find.text('입력값을 최신 내용으로 바꾸기'), findsNothing);
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }
}
