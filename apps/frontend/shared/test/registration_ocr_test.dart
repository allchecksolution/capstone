import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class RegistrationAuth implements DriverAuth {
  @override
  Future<KakaoIdentity?> signIn() async =>
      const KakaoIdentity(userId: 'new-user', accessToken: 'provider-token');
  @override
  Future<void> signOut() async {}
}

class RegistrationBackend implements FrontendBackend {
  @override
  void clearSession() {}
  @override
  Future<ApiData> call(String operation, [ApiData data = const {}]) async =>
      operation == 'drivers/business-check'
      ? {'verified': true, 'verificationId': 'check'}
      : {};
}

class FakeBusinessScanner extends BusinessDocumentScanner {
  bool fail = false;
  @override
  bool get supported => true;
  @override
  Future<BusinessDocumentFields?> scan({required bool camera}) async {
    if (fail) throw StateError('인식하지 못했습니다. 직접 입력해 주세요.');
    return BusinessDocumentFields({
      'businessNumber': '123-45-67890',
      'company': '인식된 회사',
      'owner': '김기사',
    });
  }
}

Future<void> tap(WidgetTester tester, String label) async {
  await tester.ensureVisible(find.text(label));
  await tester.tap(find.text(label));
  await tester.pumpAndSettle();
}

Future<void> businessStep(
  WidgetTester tester,
  AppStore store,
  FakeBusinessScanner scanner,
) async {
  await tester.pumpWidget(
    AllcheckApp(
      role: AppRole.driver,
      store: store,
      driverAuth: RegistrationAuth(),
      backend: RegistrationBackend(),
      onKakaoAuthenticated: (_) async => null,
      businessScanner: scanner,
    ),
  );
  await tap(tester, '카카오 로그인');
  await tap(tester, '계속');
  for (final e in {
    'name': '기사',
    'phone': '01012345678',
    'email': 'driver@example.com',
    'address': '서울시',
    'vehicle': '12가3456',
  }.entries) {
    await tester.enterText(find.byKey(ValueKey('register//${e.key}')), e.value);
  }
  await tap(tester, '다음');
}

void main() {
  testWidgets(
    'OCR appears at business step and preserves existing values until selected',
    (tester) async {
      final store = AppStore(AppRole.driver);
      await businessStep(tester, store, FakeBusinessScanner());
      expect(find.text('사업자등록증 촬영'), findsOneWidget);
      await tester.enterText(
        find.byKey(const ValueKey('register//company')),
        '기존 회사',
      );
      store.draft.addAll({'businessChecked': true, 'checkedNumber': 'old'});
      await tester.ensureVisible(find.text('사진에서 사업자등록증 선택'));
      await tester.tap(find.text('사진에서 사업자등록증 선택'));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.text('인식한 사업자 정보 확인'), findsOneWidget);
      expect(find.text('1234567890'), findsOneWidget);
      await tap(tester, '선택 항목 반영');
      expect(
        tester
            .widget<TextFormField>(
              find.byKey(const ValueKey('register//company')),
            )
            .controller!
            .text,
        '기존 회사',
      );
      expect(
        tester
            .widget<TextFormField>(
              find.byKey(const ValueKey('register//businessNumber')),
            )
            .controller!
            .text,
        '1234567890',
      );
      expect(store.draft['businessChecked'], isNull);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
  testWidgets(
    'OCR failure leaves manual entry usable and step three has no other fields',
    (tester) async {
      final store = AppStore(AppRole.driver);
      await businessStep(tester, store, FakeBusinessScanner()..fail = true);
      await tap(tester, '사업자등록증 촬영');
      expect(find.text('인식하지 못했습니다. 직접 입력해 주세요.'), findsOneWidget);
      for (final e in {
        'businessNumber': '123-45-67890',
        'openedOn': '2020-01-15',
        'company': '수동 회사',
        'owner': '김기사',
        'companyPhone': '021234567',
        'businessType': '서비스',
        'businessItem': '수리',
        'companyAddress': '서울시',
      }.entries) {
        await tester.enterText(
          find.byKey(ValueKey('register//${e.key}')),
          e.value,
        );
      }
      await tap(tester, '사업자 정보 확인');
      store.draft.addAll({'otherRegion': '이전 지역', 'otherEquipment': '이전 장비'});
      await tap(tester, '다음');
      expect(find.text('가입 정보 등록 · 3 / 4'), findsOneWidget);
      expect(find.textContaining('기타 활동 지역'), findsNothing);
      expect(find.textContaining('기타 장비'), findsNothing);
      expect(find.byType(TextFormField), findsNothing);
      await tap(tester, '막힘');
      await tap(tester, '서울 강남구');
      await tap(tester, '다음');
      expect(store.draft.containsKey('otherRegion'), false);
      expect(store.draft.containsKey('otherEquipment'), false);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
