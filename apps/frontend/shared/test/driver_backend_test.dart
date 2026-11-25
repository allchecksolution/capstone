import 'dart:convert';
import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

const profile = <String, dynamic>{
  'name': '기사',
  'phone': '01012345678',
  'email': 'driver@example.com',
  'address': '서울',
  'vehicle': '12가3456',
  'bank': '은행',
  'bankAccount': '1234567890',
  'bankOwner': '기사',
  'taxEmail': 'tax@example.com',
  'specialties': ['막힘'],
  'regions': ['서울 강남구'],
  'equipment': ['내시경 카메라'],
  'company': '설비',
  'businessNumber': '1234567890',
  'openedOn': '2020-01-15',
  'owner': '대표자',
  'companyPhone': '0212345678',
  'businessType': '서비스',
  'businessItem': '배관',
  'companyAddress': '서울',
  'businessChecked': true,
  'checkedIdentity': 'local-only',
};

http.Response jsonResponse(Object body, [int status = 200]) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
http.Response tokens() => jsonResponse({
  'accessToken': 'service-token',
  'refreshToken': 'refresh-token',
  'user': {'accountId': 11, 'userType': 'DRIVER', 'role': null},
});
http.Response noRegistration() =>
    jsonResponse({'code': 'DRIVER_NOT_FOUND'}, 404);

void main() {
  for (final invalid in <Map<String,dynamic>>[
    {'specialties':['막힘','내벽 누수','외벽 누수','방수']},
    {'bankAccount':'123 456'},
    {'bankAccount':'123'},
    {'companyPhone':'12345'},
  ]) {
    test('invalid registration is explained before POST (${invalid.keys.single})', () async {
      var posts = 0;
      final backend = DriverOnboardingBackend('https://api.example.com/api/v1', client: MockClient((request) async {
        posts++;
        return jsonResponse({});
      }));
      await expectLater(backend.call('drivers/register', {
        'profile': {...profile, ...invalid}, 'requestId':'test-key',
      }), throwsA(isA<ServiceFailure>().having((error)=>error.status,'status',400)));
      expect(posts,0);
      backend.close();
    });
  }
  test('registration validation points to safe field labels without echoing response values', () async {
    final backend = DriverOnboardingBackend('https://api.example.com/api/v1', client: MockClient((request) async => jsonResponse({
      'code':'INVALID_INPUT_VALUE', 'message':'private response data',
      'details':[
        {'field':'profile.specialties','reason':'private original value'},
        {'field':'profile.settlementAccount.accountNumber','reason':'private bank value'},
        {'field':'private-token-field','reason':'private token'},
      ],
    },400)));
    await expectLater(backend.call('drivers/register', {'profile':profile,'requestId':'test-key'}),
      throwsA(isA<ServiceFailure>()
        .having((error)=>error.message,'specialty hint',contains('전문 분야(1~3개)'))
        .having((error)=>error.message,'bank hint',contains('계좌번호'))
        .having((error)=>error.message,'no raw values',isNot(contains('private')))));
    backend.close();
  });
  test('registration server errors are distinct from invalid input', () async {
    final backend = DriverOnboardingBackend('https://api.example.com/api/v1', client: MockClient((request) async => jsonResponse({
      'code':'INTERNAL_SERVER_ERROR','message':'private SQL details',
    },500)));
    await expectLater(backend.call('drivers/register', {'profile':profile,'requestId':'test-key'}),
      throwsA(isA<ServiceFailure>()
        .having((error)=>error.message,'server failure',contains('서버 내부 오류'))
        .having((error)=>error.message,'no SQL',isNot(contains('SQL')))));
    backend.close();
  });
  for (final code in ['KAKAO_AUTH_FAILED', 'KAKAO_SERVICE_UNAVAILABLE']) {
    test('Kakao verification error $code is safe and distinguishable', () async {
      final backend = DriverOnboardingBackend('https://api.example.com/api/v1',
        client: MockClient((request) async => jsonResponse({
          'code': code, 'message': 'private token and upstream response',
        }, code == 'KAKAO_AUTH_FAILED' ? 401 : 503)));
      await expectLater(backend.call('auth/kakao', {'accessToken': 'test-token'}),
        throwsA(isA<ServiceFailure>()
          .having((error) => error.code, 'error code', code)
          .having((error) => error.message, 'safe message', contains(code == 'KAKAO_AUTH_FAILED' ? '카카오 로그인부터' : '서버에서 카카오 인증'))
          .having((error) => error.message, 'no raw response', isNot(contains('private')))));
      backend.close();
    });
  }
  test(
    'driver login, business check, submission and logout use actual REST contracts',
    () async {
      Json? registration;
      var checks = 0;
      var submissions = 0;
      var loggedOut = false;
      final backend = DriverOnboardingBackend(
        'https://api.example.com/api/v1',
        client: MockClient((request) async {
          expect(request.followRedirects, isFalse);
          final path = request.url.path;
          if (path.endsWith('/auth/sessions/kakao')) {
            expect(jsonDecode(request.body), {
              'kakaoAccessToken': 'kakao-token',
            });
            return tokens();
          }
          expect(request.headers['Authorization'], 'Bearer service-token');
          if (path.endsWith('/business-verifications')) {
            checks++;
            expect(jsonDecode(request.body), {
              'businessRegistrationNumber': '1234567890',
              'openedOn': '2020-01-15',
              'representativeName': '대표자',
            });
            expect(request.url.query, isEmpty);
            return jsonResponse({
              'status': 'VERIFIED',
              'verifiedAt': '2026-11-18T00:00:00Z',
            });
          }
          if (path.endsWith('/me/registration')) {
            if (request.method == 'GET') {
              return registration == null
                  ? noRegistration()
                  : jsonResponse(registration!);
            }
            submissions++;
            expect(request.headers['Idempotency-Key'], 'same-request');
            final body = jsonDecode(request.body) as Json;
            expect(body.keys, unorderedEquals(['profile', 'business']));
            expect(body['profile']['specialties'], ['BOILER', 'WATERPROOFING']);
            expect(body['business']['openedOn'], '2020-01-15');
            expect(
              body['business']['businessRegistrationNumber'],
              '1234567890',
            );
            expect(request.body, isNot(contains('businessChecked')));
            final result = {
              'driverId': 101,
              'accountStatus': 'PENDING_APPROVAL',
              'version': 1,
              'submittedAt': '2026-11-18T00:00:00Z',
            };
            registration = {...body, ...result};
            return jsonResponse(result, 201);
          }
          if (path.endsWith('/auth/sessions/logout')) {
            expect(jsonDecode(request.body), {'refreshToken': 'refresh-token'});
            loggedOut = true;
            return http.Response('', 204);
          }
          fail('Unexpected route $path');
        }),
      );
      final login = await backend.call('auth/kakao', {
        'accessToken': 'kakao-token',
      });
      expect(login['account'], isNull);
      expect(
        (await backend.call('drivers/business-check', {
          'businessNumber': '1234567890',
          'openedOn': '2020-01-15',
          'owner': '대표자',
        }))['verified'],
        isTrue,
      );
      final result = await backend.call('drivers/register', {
        'profile': {...profile, 'businessNumber': '123-45-67890', 'specialties': ['보일러', '방수']},
        'requestId': 'same-request',
        'consent': true,
      });
      expect(result['state']['account']['status'], '승인 대기');
      expect(result['state']['account']['profile']['name'], '기사');
      expect(
        (await backend.call('workflow/state'))['state']['account']['driverId'],
        101,
      );
      expect(checks, 1);
      expect(submissions, 1);
      await backend.call('auth/logout');
      expect(loggedOut, isTrue);
      backend.close();
    },
  );

  test('only DRIVER_NOT_FOUND is treated as an unregistered driver', () async {
    final backend = DriverOnboardingBackend(
      'https://api.example.com/api/v1',
      client: MockClient((request) async {
        if (request.url.path.endsWith('/auth/sessions/kakao')) return tokens();
        return jsonResponse({'code': 'WRONG_ROUTE'}, 404);
      }),
    );
    await expectLater(
      backend.call('auth/kakao', {'accessToken': 'kakao-token'}),
      throwsA(isA<ServiceFailure>().having((e) => e.status, 'status', 404)),
    );
    backend.close();
  });

  test(
    'expired access token refreshes via REST and retries the original GET',
    () async {
      var gets = 0;
      final backend = DriverOnboardingBackend(
        'https://api.example.com/api/v1',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/auth/sessions/kakao')) {
            return tokens();
          }
          if (request.url.path.endsWith('/auth/tokens/refresh')) {
            expect(jsonDecode(request.body), {'refreshToken': 'refresh-token'});
            return jsonResponse({
              'accessToken': 'new-service-token',
              'refreshToken': 'new-refresh-token',
            });
          }
          expect(request.method, 'GET');
          expect(request.url.path, '/api/v1/drivers/me/registration');
          gets++;
          if (gets == 2) return jsonResponse({'code': 'TOKEN_EXPIRED'}, 401);
          if (gets == 3) {
            expect(
              request.headers['Authorization'],
              'Bearer new-service-token',
            );
          }
          return noRegistration();
        }),
      );
      await backend.call('auth/kakao', {'accessToken': 'kakao-token'});
      await backend.call('workflow/state');
      expect(gets, 3);
      backend.close();
    },
  );

  test(
    'failed verification does not expose service keys or create a successful result',
    () async {
      final backend = DriverOnboardingBackend(
        'https://api.example.com/api/v1',
        client: MockClient(
          (request) async => jsonResponse({
            'code': 'BUSINESS_VERIFICATION_UNAVAILABLE',
            'message': 'private-key-and-uri',
          }, 503),
        ),
      );
      await expectLater(
        backend.call('drivers/business-check', {
          'businessNumber': '1234567890',
          'openedOn': '2020-01-15',
          'owner': '대표자',
        }),
        throwsA(
          isA<ServiceFailure>()
              .having((e) => e.status, 'status', 503)
              .having(
                (e) => e.message,
                'safe message',
                isNot(contains('private-key')),
              ),
        ),
      );
      backend.close();
    },
  );
}
