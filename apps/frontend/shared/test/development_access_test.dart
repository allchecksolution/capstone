import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

// Run once normally and once with --dart-define=ALLCHECK_DEV_ACCESS=true.
void main() {
  for (final role in AppRole.values) {
    testWidgets('${role.name} development entry follows the explicit build flag', (tester) async {
      final store = AppStore(role);
      await tester.pumpWidget(AllcheckApp(role: role, store: store));
      final entry = find.text('개발용으로 입장');
      if (developmentAccessEnabled) {
        expect(entry, findsOneWidget);
        await tester.tap(entry);
        await tester.pumpAndSettle();
        expect(find.byType(NavigationBar), findsOneWidget);
        expect(find.text('카카오 로그인'), findsNothing);
        if (role == AppRole.driver) expect(store.account?['status'], '활성');
        expect(store.jobs, isEmpty);
        await tester.tap(find.text('더보기'));
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('로그아웃'));
        await tester.tap(find.text('로그아웃'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('확인'));
        await tester.pumpAndSettle();
        expect(entry, findsOneWidget);
        expect(find.byType(NavigationBar), findsNothing);
      } else {
        expect(entry, findsNothing);
        expect(find.byType(NavigationBar), findsNothing);
        expect(store.account, isNull);
      }
      await tester.pumpWidget(const SizedBox());
    });
  }
}
