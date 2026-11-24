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

Future<void> fillRegistration(WidgetTester tester, Map<String,String> fields) async {
  for(final entry in fields.entries){
    await tester.enterText(find.byKey(ValueKey('register//${entry.key}')),entry.value);
  }
}

Future<void> tapText(WidgetTester tester, String label) async {
  FocusManager.instance.primaryFocus?.unfocus();
  await tester.pumpAndSettle();
  await tester.ensureVisible(find.text(label));
  await tester.pumpAndSettle();
  await tester.tap(find.text(label));
  await tester.pumpAndSettle();
}

Future<void> tapChoice(WidgetTester tester, String label) async {
  final chip = find.widgetWithText(FilterChip, label);
  await Scrollable.ensureVisible(tester.element(chip), alignment: 0.5);
  await tester.pumpAndSettle();
  await tester.tap(chip);
  await tester.pumpAndSettle();
}

class RegistrationBackend implements FrontendBackend {
  final operations=<String>[];
  final businessChecks=<Json>[];
  final state=AppStore.empty(AppRole.driver);
  bool rejectSubmission=false;
  @override void clearSession(){}
  @override Future<Json> call(String operation,[Json data=const {}])async{
    operations.add(operation);
    if(operation=='drivers/business-check'){businessChecks.add(Map<String,dynamic>.from(data));return {'verified':true};}
    if(operation=='notifications/list')return {'items':<Json>[]};
    if(operation=='drivers/register'){
      if(rejectSubmission)throw const ServiceFailure(503,'가입 신청을 제출하지 못했습니다.');
      state['account']={'status':'승인 대기','profile':data['profile']};
    }
    return {'state':state};
  }
}

void main() {
  testWidgets('backend login failure preserves the safe error and allows retry', (tester) async {
    final auth = FakeAuth();
    const safeMessage = '서버에서 카카오 인증을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.';
    await tester.pumpWidget(AllcheckApp(role: AppRole.driver,
      store: AppStore(AppRole.driver), driverAuth: auth,
      onKakaoAuthenticated: (_) async => throw const ServiceFailure(503, safeMessage)));
    await tapLogin(tester);
    auth.result.complete(identity);
    await tester.pumpAndSettle();
    await tester.tap(find.text('계속'));
    await tester.pumpAndSettle();
    expect(find.text(safeMessage), findsOneWidget);
    expect(find.byType(NavigationBar), findsNothing);
    expect(tester.widget<FilledButton>(find.widgetWithText(FilledButton, '계속')).onPressed, isNotNull);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('registration prefills Kakao nickname and remembers the edited name on next login', (tester) async {
    final store=AppStore(AppRole.driver);
    const namedIdentity=KakaoIdentity(userId:'nickname-user',accessToken:'private-token',nickname:'카카오별명');
    for(var attempt=0;attempt<2;attempt++){
      final auth=FakeAuth();
      await tester.pumpWidget(AllcheckApp(role:AppRole.driver,store:store,driverAuth:auth));
      await tapLogin(tester);auth.result.complete(namedIdentity);await tester.pumpAndSettle();
      await tester.tap(find.text('계속'));await tester.pumpAndSettle();
      final nameField=find.byKey(const ValueKey('register//name'));
      expect(tester.widget<TextFormField>(nameField).controller!.text,attempt==0?'카카오별명':'내 이름');
      if(attempt==0){
        await fillRegistration(tester,{'name':'   ','phone':'01012345678','email':'driver@example.com','address':'서울 강남구','vehicle':'12가3456'});
        await tapText(tester,'다음');
        expect(find.text('필수 항목입니다.'),findsOneWidget);
        await tester.enterText(nameField,'내 이름');
        await tapText(tester,'다음');
        expect(store.displayNames['nickname-user'],'내 이름');
        await tester.pump(const Duration(seconds:5));await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('로그아웃'));await tester.tap(find.text('로그아웃'));await tester.pumpAndSettle();
        await tester.tap(find.text('확인'));await tester.pumpAndSettle();
        expect(store.account,isNull);
      }
      await tester.pumpWidget(const SizedBox());
    }
    expect(store.data.toString(),isNot(contains('private-token')));
  });

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

  testWidgets('SDK-only first login opens registration without inventing a pending application', (tester) async {
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
    expect(find.byType(NavigationBar), findsNothing);
    expect(find.text('가입 정보 등록 · 1 / 4'), findsOneWidget);
    expect(find.text('관리자 승인 대기'), findsNothing);
    expect(store.account?['userId'], '123');
    expect(store.account?['status'], '미신청');
    expect(store.profile['name'], isNot('이전 사용자'));
    expect(store.data.toString(), isNot(contains(identity.accessToken)));
    expect(find.text('승인 상태 미리보기'), findsNothing);
    await tester.ensureVisible(find.text('로그아웃'));
    await tester.tap(find.text('로그아웃'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('확인'));
    await tester.pumpAndSettle();
    expect(auth.logouts, 1);
    expect(find.text('카카오 로그인'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });

  for(final rejected in [false,true]){
    testWidgets('registration becomes pending only after successful submission (failure=$rejected)', (tester) async {
      final auth=FakeAuth();final store=AppStore(AppRole.driver);
      final backend=RegistrationBackend()..rejectSubmission=rejected;
      await tester.pumpWidget(AllcheckApp(role:AppRole.driver,store:store,driverAuth:auth,
        backend:backend,onKakaoAuthenticated:(_)async=>null));
      await tapLogin(tester);auth.result.complete(identity);await tester.pumpAndSettle();
      await tapText(tester,'계속');
      expect(find.text('가입 정보 등록 · 1 / 4'),findsOneWidget);
      expect(store.account,isNull);
      await fillRegistration(tester,{'name':'신청 기사','phone':'01012345678','email':'driver@example.com','address':'서울 강남구','vehicle':'12가3456'});
      await tapText(tester,'다음');
      await fillRegistration(tester,{'businessNumber':'1234567890','openedOn':'2020-01-15','company':'올체크','owner':'대표자','companyPhone':'0212345678','businessType':'서비스','businessItem':'수리','companyAddress':'서울 서초구'});
      await tapText(tester,'사업자 정보 확인');
      await tapText(tester,'다음');
      await tapText(tester,'막힘');await tapText(tester,'서울 강남구');
      if(!rejected){
        for(final extra in ['내벽 누수','외벽 누수','방수']){await tapChoice(tester,extra);}
        await tapText(tester,'다음');
        expect(find.text('가입 정보 등록 · 3 / 4'),findsOneWidget);
        expect(find.text('전문 분야는 1~3개만 선택해 주세요.'),findsOneWidget);
        ScaffoldMessenger.of(tester.element(find.byType(Form))).clearSnackBars();
        await tester.pumpAndSettle();
        for(final extra in ['내벽 누수','외벽 누수','방수']){await tapChoice(tester,extra);}
      }
      await tapText(tester,'다음');
      await fillRegistration(tester,{'bank':'테스트 은행','bankAccount':'123456789','bankOwner':'신청 기사','taxEmail':'tax@example.com'});
      await tapText(tester,'입력 정보 확인 및 가입 신청에 동의합니다.');
      expect(store.account,isNull);
      expect(backend.operations,isNot(contains('drivers/register')));
      await tapText(tester,'가입 신청');
      if(rejected){
        expect(store.account,isNull);
        expect(find.text('가입 정보 등록 · 4 / 4'),findsOneWidget);
        expect(find.text('관리자 검토가 진행 중입니다.'),findsNothing);
      }else{
        expect(store.account?['status'],'승인 대기');
        expect(store.profile['name'],'신청 기사');
        expect(find.text('관리자 검토가 진행 중입니다.'),findsOneWidget);
      }
      expect(find.byType(NavigationBar),findsNothing);
      await tester.pumpWidget(const SizedBox());
    });
  }

  testWidgets('business check requires a real date and rechecks all three identity fields after edits', (tester) async {
    final previousHitTestSetting=WidgetController.hitTestWarningShouldBeFatal;
    WidgetController.hitTestWarningShouldBeFatal=true;
    addTearDown(()=>WidgetController.hitTestWarningShouldBeFatal=previousHitTestSetting);
    final auth=FakeAuth();final store=AppStore(AppRole.driver);final backend=RegistrationBackend();
    await tester.pumpWidget(AllcheckApp(role:AppRole.driver,store:store,driverAuth:auth,backend:backend,onKakaoAuthenticated:(_)async=>null));
    await tapLogin(tester);auth.result.complete(identity);await tester.pumpAndSettle();await tapText(tester,'계속');
    await fillRegistration(tester,{'name':'기사','phone':'01012345678','email':'driver@example.com','address':'서울','vehicle':'12가3456'});
    await tapText(tester,'다음');
    const business={'businessNumber':'123-45-67890','openedOn':'2020-02-30','owner':'대표자','company':'설비','companyPhone':'0212345678','businessType':'서비스','businessItem':'수리','companyAddress':'서울'};
    await fillRegistration(tester,business);await tapText(tester,'사업자 정보 확인');
    expect(backend.businessChecks,isEmpty);
    await fillRegistration(tester,{'openedOn':'2020-01-15'});await tapText(tester,'사업자 정보 확인');
    expect(backend.businessChecks.single,{'businessNumber':'1234567890','openedOn':'2020-01-15','owner':'대표자'});
    for(final change in [{'businessNumber':'1234567891'},{'openedOn':'2020-01-16'},{'owner':'다른 대표자'}]){
      ScaffoldMessenger.of(tester.element(find.byType(Form))).clearSnackBars();
      await tester.pumpAndSettle();
      await fillRegistration(tester,{...business,'openedOn':'2020-01-15',...change});
      await tapText(tester,'다음');
      expect(find.text('가입 정보 등록 · 2 / 4'),findsOneWidget);
      expect(store.account,isNull);
      expect(find.text('사업자등록번호·개업일자·대표자명을 다시 확인해 주세요.'),findsOneWidget);
    }
    expect(backend.operations,isNot(contains('drivers/register')));
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('SDK-only business check does not invent verification or a pending application', (tester) async {
    final auth=FakeAuth();final store=AppStore(AppRole.driver);
    await tester.pumpWidget(AllcheckApp(role:AppRole.driver,store:store,driverAuth:auth));
    await tapLogin(tester);auth.result.complete(identity);await tester.pumpAndSettle();
    await tapText(tester,'계속');
    await fillRegistration(tester,{'name':'기사','phone':'01012345678','email':'driver@example.com','address':'서울 강남구','vehicle':'12가3456'});
    await tapText(tester,'다음');
    await fillRegistration(tester,{'businessNumber':'1234567890','openedOn':'2020-01-15','owner':'대표자'});
    await tapText(tester,'사업자 정보 확인');
    expect(find.text('서버 연결 후 사업자 정보를 확인할 수 있습니다.'),findsOneWidget);
    expect(store.draft['businessChecked'],isNot(true));
    expect(store.account?['status'],'미신청');
    await tester.pumpWidget(const SizedBox());
  });

  for(final pending in [false,true]){
    testWidgets('temporary home obeys its access flag (pending=$pending)', (tester) async {
      final auth=FakeAuth();var saves=0;
      final store=AppStore(AppRole.driver,save:(_)async{saves++;});
      final backend=pending?RegistrationBackend():null;
      final account=<String,dynamic>{'status':'승인 대기','profile':<String,dynamic>{'name':'대기 기사'}};
      await tester.pumpWidget(AllcheckApp(role:AppRole.driver,store:store,driverAuth:auth,backend:backend,
        onKakaoAuthenticated:pending?(_)async=>account:null));
      await tapLogin(tester);auth.result.complete(identity);await tester.pumpAndSettle();
      await tapText(tester,'계속');
      final label=pending?'임시로 관리자 검토 건너뛰기':'개발용: 승인 대기 건너뛰기';
      if(pending?temporaryReviewAccessEnabled:developmentAccessEnabled){
        if(pending){
          expect(find.text(label),findsOneWidget);
          expect(find.widgetWithText(FilledButton,label),findsOneWidget);
          final review=tester.getTopLeft(find.text('관리자 검토가 진행 중입니다.'));
          final skip=tester.getTopLeft(find.text(label));
          final logout=tester.getTopLeft(find.text('로그아웃'));
          expect(skip.dy,greaterThan(review.dy));
          expect(skip.dy,lessThan(logout.dy));
        }
        if(!pending)await fillRegistration(tester,{'name':'입력 중인 기사'});
        final savedBefore=saves;final callsBefore=backend?.operations.length;
        await tapText(tester,label);
        expect(find.byType(NavigationBar),findsOneWidget);
        expect(store.profile['name'],pending?'대기 기사':'입력 중인 기사');
        expect(store.transientSession,isTrue);
        expect(saves,savedBefore);
        expect(account['status'],'승인 대기');
        await tester.pump(const Duration(seconds:16));await tester.pumpAndSettle();
        expect(backend?.operations.length,callsBefore);
        await tapText(tester,'더보기');await tapText(tester,'로그아웃');await tapText(tester,'확인');
        expect(store.account,isNull);
        expect(store.transientSession,isFalse);
        expect(saves,savedBefore);
      }else{
        expect(find.text(label),findsNothing);
        expect(find.byType(NavigationBar),findsNothing);
      }
      await tester.pumpWidget(const SizedBox());
    });
  }

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

  testWidgets('server approved account can enter workflow', (tester) async {
    final auth=FakeAuth();
    await tester.pumpWidget(AllcheckApp(role:AppRole.driver,store:AppStore(AppRole.driver),driverAuth:auth,
      onKakaoAuthenticated:(_)async=><String,dynamic>{'status':'활성','profile':<String,dynamic>{'name':'승인된 기사'}}));
    await tapLogin(tester);auth.result.complete(identity);await tester.pumpAndSettle();
    await tester.tap(find.text('계속'));await tester.pumpAndSettle();
    expect(find.byType(NavigationBar),findsOneWidget);
    expect(find.text('승인된 기사님, 안녕하세요'),findsOneWidget);
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
