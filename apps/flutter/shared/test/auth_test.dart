import 'dart:async';
import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeAuth implements DriverAuth {
  final result = Completer<KakaoIdentity?>();
  int calls = 0;
  int logouts = 0;
  @override
  Future<KakaoIdentity?> signIn() { calls++; return result.future; }
  @override
  Future<void> signOut() async { logouts++; }
}

const identity = KakaoIdentity(userId: '123', accessToken: 'not-a-real-token');

Future<void> tapLogin(WidgetTester tester) async {
  await tester.ensureVisible(find.text('카카오 로그인'));
  await tester.tap(find.text('카카오 로그인'));
  await tester.pump();
}

void main() {
  testWidgets('cancel stays signed out and blocks duplicate login requests', (tester) async {
    final auth = FakeAuth();
    await tester.pumpWidget(AllcheckApp(role: AppRole.driver,
      store: AppStore(AppRole.driver), driverAuth: auth));
    await tapLogin(tester);
    await tapLogin(tester);
    expect(auth.calls, 1);
    auth.result.complete(null);
    await tester.pumpAndSettle();
    expect(find.text('카카오 계정이 확인되었습니다.'), findsNothing);
    expect(find.byType(NavigationBar), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('Kakao identity cannot authorize a saved active account', (tester) async {
    final auth = FakeAuth();
    final store = AppStore(AppRole.driver);
    store.data['account'] = <String, dynamic>{'status': '활성', 'profile': <String, dynamic>{'name': '이전 사용자'}};
    await tester.pumpWidget(AllcheckApp(role: AppRole.driver, store: store, driverAuth: auth));
    await tapLogin(tester);
    auth.result.complete(identity);
    await tester.pumpAndSettle();
    expect(find.text('카카오 계정이 확인되었습니다.'), findsOneWidget);
    await tester.tap(find.text('계속'));
    await tester.pumpAndSettle();
    expect(find.text('로그인 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
    expect(find.text('승인 상태 미리보기'), findsNothing);
    await tester.tap(find.text('로그아웃'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('확인'));
    await tester.pumpAndSettle();
    expect(auth.logouts, 1);
    expect(find.text('카카오 로그인'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('server pending account remains gated and replaces old local state', (tester) async {
    final auth = FakeAuth();
    final store = AppStore(AppRole.driver);
    store.jobs.add(<String, dynamic>{'id': 'old-user-job'});
    await tester.pumpWidget(AllcheckApp(role: AppRole.driver, store: store, driverAuth: auth,
      onKakaoAuthenticated: (value) async {
        expect(value.accessToken, identity.accessToken);
        return <String, dynamic>{'status': '승인 대기', 'profile': <String, dynamic>{}};
      }));
    await tapLogin(tester);
    auth.result.complete(identity);
    await tester.pumpAndSettle();
    await tester.tap(find.text('계속'));
    await tester.pumpAndSettle();
    expect(store.jobs, isEmpty);
    expect(find.text('관리자 검토가 진행 중입니다.'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('login failure permits retry without entering workflow', (tester) async {
    final auth = FakeAuth();
    await tester.pumpWidget(AllcheckApp(role: AppRole.driver,
      store: AppStore(AppRole.driver), driverAuth: auth));
    await tapLogin(tester);
    auth.result.completeError(StateError('로그인 실패'));
    await tester.pumpAndSettle();
    expect(find.text('로그인 실패'), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
    final button = tester.widget<FilledButton>(find.widgetWithText(FilledButton, '카카오 로그인'));
    expect(button.onPressed, isNotNull);
    await tester.pumpWidget(const SizedBox());
  });
}
