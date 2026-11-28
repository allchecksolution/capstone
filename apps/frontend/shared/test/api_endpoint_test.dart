import 'dart:convert';

import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  for (final suffix in ['', '/', '/api/v1', '/api/v1/']) {
    final baseUrl = 'https://api.example.com:8443$suffix';

    test('admin uses one API prefix for $suffix', () async {
      final backend = AdminRestBackend(
        baseUrl,
        client: MockClient((request) async {
          expect(request.method, 'GET');
          expect(
            request.url.toString(),
            'https://api.example.com:8443/api/v1/admin-accounts?page=2&size=20',
          );
          return http.Response('{}', 200);
        }),
      );
      addTearDown(backend.close);
      await backend.call('admin-accounts/list', {'page': 2});
    });

    test('driver uses the same API prefix for $suffix', () async {
      final backend = DriverOnboardingBackend(
        baseUrl,
        client: MockClient((request) async {
          expect(request.method, 'POST');
          expect(
            request.url.toString(),
            'https://api.example.com:8443/api/v1/drivers/business-verifications',
          );
          return http.Response('{"status":"VERIFIED"}', 200);
        }),
      );
      addTearDown(backend.close);
      await backend.call('drivers/business-check', {
        'businessNumber': '1234567890',
        'openedOn': '2020-01-15',
        'owner': 'test',
      });
    });

    test('receptions use the same API prefix for $suffix', () async {
      final backend = SpringFrontendBackend(
        baseUrl,
        client: MockClient((request) async {
          expect(request.method, 'GET');
          expect(
            request.url.toString(),
            'https://api.example.com:8443/api/v1/receptions?page=2&size=20',
          );
          expect(request.headers['Authorization'], 'Bearer service-token');
          return http.Response(
            jsonEncode({
              'items': [],
              'page': {'page': 2, 'totalPages': 0},
            }),
            200,
          );
        }),
      )..setServiceSession('service-token');
      addTearDown(backend.close);
      await backend.call('receptions/list', {'page': 2});
    });
  }

  test('all REST adapters reject unexpected paths and unsafe addresses', () {
    for (final url in [
      '',
      'http://api.example.com',
      'https://user:password@api.example.com',
      'https://api.example.com/api/frontend',
      'https://api.example.com/api/v1/api/v1',
      'https://api.example.com?token=secret',
      'https://api.example.com#fragment',
    ]) {
      expect(() => AdminRestBackend(url), throwsArgumentError);
      expect(() => DriverOnboardingBackend(url), throwsArgumentError);
      expect(() => SpringFrontendBackend(url), throwsArgumentError);
    }
  });
}
