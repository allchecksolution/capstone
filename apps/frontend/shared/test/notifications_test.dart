import 'dart:async';
import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class TestPush implements AppPush{
  final incoming=StreamController<AppAlert>.broadcast(),taps=StreamController<AppAlert>.broadcast();
  final refreshed=StreamController<String>.broadcast();
  AppAlert? initial;
  @override Stream<AppAlert> get received=>incoming.stream;
  @override Stream<AppAlert> get opened=>taps.stream;
  @override Stream<String> get tokens=>refreshed.stream;
  @override Future<AppAlert?> start()async=>initial;
  @override Future<String> requestPermission()async=>'authorized';
  @override Future<String?> token()async=>'device-token';
  @override Future<void> deleteToken()async{}
  @override Future<void> dispose()async{await incoming.close();await taps.close();await refreshed.close();}
}
class TestBackend implements FrontendBackend{
  final operations=<String>[];
  Json state=AppStore.empty(AppRole.admin);
  bool expired=false;
  @override void clearSession(){}
  @override Future<Json> call(String operation,[Json data=const {}])async{
    operations.add(operation);
    if(operation=='notifications/list')return {'items':<Json>[]};
    if(operation=='notifications/open'){
      if(expired)throw const ServiceFailure(410,'만료된 요청입니다.');
      return {'state':state,'page':'reception','resourceId':'r1'};
    }
    return {'state':state,'name':'관리자'};
  }
}
const alert=AppAlert(id:'n1',type:'analysis_completed',resourceId:'r1',role:'admin');
Future<void> login(WidgetTester tester)async{
  await tester.enterText(find.byType(TextFormField).at(0),'admin');
  await tester.enterText(find.byType(TextFormField).at(1),'password');
  await tester.ensureVisible(find.text('로그인'));
  await tester.tap(find.text('로그인'));await tester.pumpAndSettle();
}
void main(){
  testWidgets('cold notification waits for ID/password login, then fetches authorized details',(tester)async{
    final push=TestPush()..initial=alert;
    final backend=TestBackend();
    (backend.state['consultations'] as List).add({'id':'r1','status':'분석 완료','stt':'상담 원문','analysis':'원본 증상'});
    await tester.pumpWidget(AllcheckApp(role:AppRole.admin,store:AppStore(AppRole.admin),backend:backend,push:push));
    await tester.pumpAndSettle();
    expect(backend.operations, isEmpty);
    await login(tester);
    expect(backend.operations,contains('notifications/open'));
    expect(backend.operations,contains('notifications/read'));
    expect(find.text('상담 원문'),findsOneWidget);
    expect(find.text('원본 증상'),findsOneWidget);
    expect(find.text('인증 코드'),findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('foreground duplicate is shown once and wrong-role events are ignored',(tester)async{
    final push=TestPush();final backend=TestBackend();
    await tester.pumpWidget(AllcheckApp(role:AppRole.admin,store:AppStore(AppRole.admin),backend:backend,push:push));
    await login(tester);
    push.incoming.add(alert);push.incoming.add(alert);
    push.incoming.add(const AppAlert(id:'wrong',type:'dispatch_requested',resourceId:'j1',role:'driver'));
    await tester.pumpAndSettle();
    expect(find.text(alert.title),findsOneWidget);
    expect(find.text('새로운 배차 요청이 도착했습니다.'),findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('expired notification stays out of details and is not marked read',(tester)async{
    final push=TestPush();final backend=TestBackend()..expired=true;
    await tester.pumpWidget(AllcheckApp(role:AppRole.admin,store:AppStore(AppRole.admin),backend:backend,push:push));
    await login(tester);push.taps.add(alert);await tester.pumpAndSettle();
    expect(find.text('만료된 요청입니다.'),findsOneWidget);
    expect(backend.operations,isNot(contains('notifications/read')));
    await tester.pumpWidget(const SizedBox());
  });
}
