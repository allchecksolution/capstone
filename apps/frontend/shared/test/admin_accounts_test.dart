import 'dart:convert';
import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  for (final bootstrap in [true, false]) {
    testWidgets('administrator menu respects bootstrap designation ($bootstrap)', (
      tester,
    ) async {
      var created = false;
      final backend = AdminRestBackend(
        'https://example.com/api/v1',
        client: MockClient((request) async {
          if (request.url.path.endsWith('/sessions/admin')) {
            expect(jsonDecode(request.body), {
              'loginId': 'admin',
              'password': 'initial',
            });
            return http.Response(
              jsonEncode({
                'accessToken': 'token',
                'refreshToken': 'refresh',
                'user': {
                  'accountId': 1,
                  'userType': 'ADMIN',
                  'isBootstrapAdmin': bootstrap,
                },
              }),
              200,
            );
          }
          if (request.url.path.endsWith('/admin-accounts')) {
            if (request.method == 'POST') {
              created = true;
              expect(jsonDecode(request.body), {
                'loginId': 'child',
                'initialPassword': ' child password ',
              });
              return http.Response('{}', 201);
            }
            return http.Response(
              '{"items":[{"accountId":1,"loginId":"admin","isBootstrapAdmin":true,"enabled":true,"version":0}],"totalElements":1}',
              200,
            );
          }
          throw StateError('Unexpected request ${request.url.path}');
        }),
      );
      await tester.pumpWidget(
        AllcheckApp(
          role: AppRole.admin,
          store: AppStore(AppRole.admin),
          backend: backend,
        ),
      );
      await tester.enterText(find.byType(TextFormField).at(0), 'admin');
      await tester.enterText(find.byType(TextFormField).at(1), 'initial');
      await tester.tap(find.widgetWithText(FilledButton, '로그인'));
      await tester.pumpAndSettle();
      // Neither account type is forced onto a first-login password change page.
      expect(find.text('기사 가입 심사'), findsOneWidget);
      expect(find.text('현재 비밀번호 *'), findsNothing);
      if (bootstrap) {
        await tester.tap(find.widgetWithText(FilledButton, '관리자 계정 관리'));
        await tester.pumpAndSettle();
        expect(find.text('삭제'), findsNothing);
        await tester.tap(find.widgetWithText(FilledButton, '관리자 추가'));
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextFormField).at(0), 'child');
        await tester.enterText(
          find.byType(TextFormField).at(1),
          ' child password ',
        );
        await tester.tap(find.widgetWithText(FilledButton, '계정 생성'));
        await tester.pumpAndSettle();
        expect(created, true);
        expect(find.text('관리자 계정을 생성했습니다.'), findsOneWidget);
      } else {
        expect(find.text('관리자 계정 관리'), findsNothing);
        expect(find.text('관리자 추가'), findsNothing);
      }
      await tester.pumpWidget(const SizedBox());
      backend.close();
    });
  }
}
