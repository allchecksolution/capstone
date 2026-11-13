import 'dart:convert';
import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main(){
  test('HTTPS and operation allowlist reject unsafe destinations',(){
    expect(()=>HttpFrontendBackend('http://example.com'),throwsArgumentError);
    expect(()=>HttpFrontendBackend('https://user:secret@example.com'),throwsArgumentError);
  });
  test('credentials issue a service bearer and redirects are disabled',()async{
    var calls=0;
    final backend=HttpFrontendBackend('https://api.example.com/frontend',client:MockClient((request)async{
      calls++;
      expect(request.followRedirects,isFalse);
      if(calls==1){expect(jsonDecode(request.body)['username'],'admin');return http.Response('{"accessToken":"service-token"}',200);}
      expect(request.headers['Authorization'],'Bearer service-token');
      return http.Response('{}',200);
    }));
    await backend.call('auth/admin-login',{'username':'admin','password':'private'});
    await backend.call('workflow/state');
    backend.close();
  });
  test('401 refreshes once and replays same idempotent command',()async{
    var calls=0;
    final backend=HttpFrontendBackend('https://api.example.com',client:MockClient((request)async{
      calls++;
      if(calls==1)return http.Response('{"accessToken":"old","refreshToken":"refresh"}',200);
      if(calls==2)return http.Response('',401);
      if(calls==3){expect(request.url.path,'/auth/refresh');return http.Response('{"accessToken":"new"}',200);}
      expect(request.headers['Authorization'],'Bearer new');
      expect(jsonDecode(request.body)['requestId'],'same-request');
      return http.Response('{}',200);
    }));
    await backend.call('auth/admin-login');
    await backend.call('dispatch/respond',{'requestId':'same-request'});
    expect(calls,4);backend.close();
  });
  test('server secrets are not exposed in error messages',()async{
    final backend=HttpFrontendBackend('https://api.example.com',client:MockClient((request)async=>http.Response('private customer address and token',403)));
    await expectLater(backend.call('workflow/state'),throwsA(isA<ServiceFailure>().having((e)=>e.message,'safe message',isNot(contains('private')))));
    backend.close();
  });
  test('malformed notification data is ignored',(){
    expect(AppAlert.parse({'type':'dispatch_requested','resourceId':'one','role':'driver'}),isNull);
    expect(AppAlert.parse({'notificationId':'one','type':'arbitrary_route','resourceId':'one','role':'driver'}),isNull);
    expect(AppAlert.parse({'notificationId':'one','type':'analysis_completed','resourceId':'one','role':'admin'})?.title,contains('분석이 완료'));
  });
}
