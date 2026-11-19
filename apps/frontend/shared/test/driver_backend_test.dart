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
  'user': {'accountId': 'account-1', 'userType': 'DRIVER', 'role': null},
});
http.Response noRegistration() =>
    jsonResponse({'code': 'DRIVER_NOT_FOUND'}, 404);

void main() {
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
            expect(body['profile']['specialties'], ['BLOCKAGE']);
            expect(body['business']['openedOn'], '2020-01-15');
            expect(
              body['business']['businessRegistrationNumber'],
              '1234567890',
            );
            expect(request.body, isNot(contains('businessChecked')));
            final result = {
              'driverId': 'driver-1',
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
        'profile': {...profile, 'businessNumber': '123-45-67890'},
        'requestId': 'same-request',
        'consent': true,
      });
      expect(result['state']['account']['status'], '승인 대기');
      expect(result['state']['account']['profile']['name'], '기사');
      expect(
        (await backend.call('workflow/state'))['state']['account']['driverId'],
        'driver-1',
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
