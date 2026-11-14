part of '../allcheck_ui.dart';

extension BackendScreens on _WorkflowShellState {
  FrontendBackend get api => widget.backend ?? (throw const ServiceFailure(0, '서버 연결 설정을 확인해 주세요.'));

  Future<void> checkBusiness(List<String> keys)async{
    if(!RegExp(r'^\d{3}-?\d{2}-?\d{5}$').hasMatch(value('businessNumber'))){message('사업자등록번호 10자리를 입력해 주세요.');return;}
    await platformTask(()async{
      final response=developmentSession?<String,dynamic>{'verified':true}:await api.call('drivers/business-check',{'businessNumber':value('businessNumber')});
      if(response['verified']!=true)throw const ServiceFailure(0,'사업자 정보를 확인하지 못했습니다. 입력 내용을 확인해 주세요.');
      await s.change(()=>s.draft.addAll({...values(keys),'businessChecked':true,'checkedNumber':value('businessNumber'),'businessVerificationId':response['verificationId']}));
      message('사업자 정보를 확인했습니다.');
    });
  }

  Widget documentPage()=>FutureBuilder<Json>(
    future:publicDocuments.putIfAbsent(page,()=>api.call('documents/get',{'kind':page})),
    builder:(context,snapshot){
      if(snapshot.connectionState!=ConnectionState.done)return const Center(child:CircularProgressIndicator());
      if(snapshot.hasError)return stack([notice('문서를 불러오지 못했습니다. 연결 상태를 확인해 주세요.',error:true),button('다시 시도',(){publicDocuments.remove(page);refresh((){});},secondary:true)]);
      return stack([heading(page=='terms'?'이용약관':'개인정보 처리방침'),SelectableText(text(snapshot.data??{},'content'))]);
    });

  void failure(Object error) {
    if(!mounted)return;
    if(error is ServiceFailure&&error.status==499)return;
    if (error is ServiceFailure && error.status == 401) {
      widget.backend?.clearSession();signedIn=false;kakaoIdentity=null;pendingAlert=null;stateVersion++;unawaited(locationSubscription?.cancel());locationSubscription=null;alerts.clear();
      history.clear();go('login',replace:true);
    }
    message(error is ServiceFailure ? error.message : '처리하지 못했습니다. 다시 시도해 주세요.');
  }

  Future<void> applyServerState(Json result) async {
    final state=result['state'];
    if(state is! Json)throw const ServiceFailure(0,'서버 응답 형식을 확인해 주세요.');
    await s.change((){
      s.restore(jsonEncode(state));
      final collection=switch(page){'detail'||'request'||'report'||'report-edit'||'report-history'||'dispatch'||'cancel'||'revisit'=>s.jobs,'reception'=>s.consultations,'driver'=>s.drivers,'cancellation'=>s.cancellations,_=>null};
      if(collection!=null&&!collection.any((row)=>row['id']==id)){page='home';id='';history.clear();}
    });
  }

  /// All server mutations use explicit commands, never a client-owned snapshot.
  /// Keep the same requestId when retrying an unchanged failed command.
  Future<bool> command(String operation,Json payload,void Function() local)async{
    if(developmentSession)return act(local,operation:operation);
    if(busy)return false;stateVersion++;refresh(()=>busy=true);
    final key='$operation:${jsonEncode(payload)}';
    final requestId=retryKeys.putIfAbsent(key,uid);
    final entityId=payload['id'];
    final collection=operation.startsWith('receptions/')?s.consultations:operation=='drivers/decide'?s.drivers:operation.startsWith('cancellations/')?s.cancellations:s.jobs;
    final entity=collection.where((row)=>row['id']==entityId).firstOrNull;
    var conflict=false;
    try{
      final response=await api.call(operation,{...payload,'requestId':requestId,
        if(entity?['version']!=null)'expectedVersion':entity!['version']});
      if(!mounted)return false;
      await applyServerState(response);lastResourceId=text(response,'resourceId');retryKeys.remove(key);return true;
    }catch(e){conflict=e is ServiceFailure&&[409,410].contains(e.status);failure(e);return false;}
    finally{if(mounted){refresh(()=>busy=false);if(conflict)await refreshState();drainPending();}}
  }

  Future<void> loginAdmin()async{
    if(busy||!(formKey.currentState?.validate()??false))return;
    refresh(()=>busy=true);
    try{
      // ID/password only, per current product decision. No public signup.
      final response=await api.call('auth/admin-login',{'username':value('username'),'password':value('password')});
      if(!mounted)return;
      await applyServerState(response);
      if(!mounted)return;
      adminName=text(response,'name',value('username'));
      controller('password').clear();signedIn=true;developmentSession=false;
      history.clear();go('home',replace:true);
    }catch(e){failure(e);}
    finally{if(mounted){refresh(()=>busy=false);if(signedIn)unawaited(afterAuthentication());}}
  }

  Future<void> refreshState()async{
    if(!signedIn||developmentSession||widget.backend==null||busy||refreshingState)return;
    refreshingState=true;final version=stateVersion;
    try{final response=await api.call('workflow/state',{'role':widget.role.name});if(mounted&&signedIn&&!busy&&version==stateVersion)await applyServerState(response);}
    catch(e){failure(e);}
    finally{refreshingState=false;}
  }

  Future<void> loadCandidates()async{
    if(developmentSession){refresh((){});return;}
    if(busy)return;refresh(()=>busy=true);
    try{final response=await api.call('dispatch/candidates',{'id':id,'expanded':expanded});if(mounted)await applyServerState(response);}
    catch(e){failure(e);}finally{if(mounted){refresh(()=>busy=false);drainPending();}}
  }

  Future<void> closeSession()async{
    stateVersion++;signedIn=false;kakaoIdentity=null;pendingAlert=null;
    await locationSubscription?.cancel();locationSubscription=null;
    try{await detachDevice();}catch(_){}
    widget.backend?.clearSession();alerts.clear();seenAlerts.clear();retryKeys.clear();
    await s.change(()=>s.data=AppStore.empty(widget.role));
  }

  Future<void> changeAvailability(String availability)async{
    if(await command('drivers/availability',{'availability':availability},()=>s.data['availability']=availability)){
      if(availability=='휴식'){await locationSubscription?.cancel();locationSubscription=null;}
    }
  }

  Future<void> initializePush()async{
    final push=appPush;if(push==null)return;
    pushSubscriptions.add(push.received.listen((a)=>receiveAlert(a)));
    pushSubscriptions.add(push.opened.listen((a)=>openAlert(a)));
    pushSubscriptions.add(push.tokens.listen((token){unawaited(registerDevice(token));}));
    try{final initial=await push.start();if(mounted&&initial!=null){pendingAlert=initial;if(signedIn)unawaited(afterAuthentication());}}
    catch(_){if(mounted)refresh(()=>pushStatus='알림 서비스를 사용할 수 없습니다. 설정 후 다시 시도해 주세요.');}
  }

  void drainPending(){
    final pending=pendingAlert;
    if(mounted&&signedIn&&!busy&&!developmentSession&&pending!=null){pendingAlert=null;unawaited(openAlert(pending));}
  }

  Future<void> afterAuthentication()async{
    if(!mounted||!signedIn||developmentSession||sdkOnlySession||afterAuthRunning)return;
    afterAuthRunning=true;try{
    await loadInbox();
    try{final token=await appPush?.token();if(token!=null)await registerDevice(token);}catch(_){if(mounted)refresh(()=>pushStatus='알림 연결을 다시 시도해 주세요.');}
    final pending=pendingAlert;
    if(pending!=null&&mounted){pendingAlert=null;await openAlert(pending);}
    }finally{afterAuthRunning=false;drainPending();}
  }

  Future<void> enablePush()async{
    if(busy)return;
    final push=appPush;
    if(push==null){message('알림 서비스를 사용할 수 없습니다. 설정을 확인해 주세요.');return;}
    try{
      final status=await push.requestPermission();
      if(!mounted)return;
      refresh(()=>pushStatus=switch(status){'authorized'||'provisional'=>'알림 권한이 허용되었습니다.','denied'=>'알림 권한이 거부되었습니다. 기기 설정에서 허용해 주세요.',_=>'알림 권한을 확인할 수 없습니다.'});
      final token=await push.token();if(token!=null)await registerDevice(token);
    }catch(_){message('알림 설정에 실패했습니다. 다시 시도해 주세요.');}
  }

  Future<void> registerDevice(String token)async{
    if(!signedIn||developmentSession||widget.backend==null||registeringToken)return;
    registeringToken=true;
    try{await api.call('devices/register',{'token':token,'role':widget.role.name,'platform':defaultTargetPlatform.name});deviceToken=token;if(mounted)refresh(()=>pushStatus='알림 수신이 연결되었습니다.');}
    catch(_){if(mounted)refresh(()=>pushStatus='알림 연결을 다시 시도해 주세요.');}
    finally{registeringToken=false;}
  }

  Future<void> detachDevice()async{
    try{if(!developmentSession&&deviceToken!=null&&widget.backend!=null)await api.call('devices/unregister',{'token':deviceToken});}
    finally{deviceToken=null;await appPush?.deleteToken();}
  }

  Future<void> loadInbox()async{
    if(!signedIn||developmentSession||widget.backend==null)return;
    try{
      final response=await api.call('notifications/list',{'role':widget.role.name});
      final parsed=records(response['items']).map(AppAlert.parse).whereType<AppAlert>().where((a)=>a.role==widget.role.name);
      if(mounted)refresh((){alerts.clear();for(final alert in parsed){if(!alerts.any((a)=>a.id==alert.id))alerts.add(alert);}});
    }catch(e){failure(e);}
  }

  void receiveAlert(AppAlert alert){
    if(!mounted||!signedIn||developmentSession||alert.role!=widget.role.name)return;
    if(seenAlerts.contains(alert.id))return;
    seenAlerts.add(alert.id);if(seenAlerts.length>200)seenAlerts.remove(seenAlerts.first);
    refresh((){if(!alerts.any((a)=>a.id==alert.id))alerts.insert(0,alert);});
    final category=switch(alert.type){'analysis_completed'||'analysis_failed'||'dispatch_requested'=>0,'dispatch_updated'||'dispatch_cancelled'=>1,'work_updated'=>2,'chat_received'=>admin?2:3,_=>3};
    if((s.data['notifications'] as List)[category]!=true)return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content:Text(alert.title),action:SnackBarAction(label:'확인',onPressed:()=>openAlert(alert))));
  }

  Future<void> openAlert(AppAlert alert)async{
    if(!mounted||alert.role!=widget.role.name)return;
    if(!signedIn){pendingAlert=alert;message('알림을 확인하려면 로그인해 주세요.');return;}
    if(developmentSession||busy){pendingAlert=alert;return;}
    // No route names/addresses/phone numbers from a push are trusted. Fetch an
    // authorized projection and re-check expired/assigned requests on server.
    refresh(()=>busy=true);
    try{
      final response=await api.call('notifications/open',{'notificationId':alert.id});
      if(!mounted)return;
      await applyServerState(response);
      final target=text(response,'page'),item=text(response,'resourceId');
      final allowed=admin?['reception','detail','driver','chat','cancellation','report']:['request','detail','chat','home'];
      if(!allowed.contains(target))throw const ServiceFailure(0,'알림 대상 화면을 확인할 수 없습니다.');
      if(target=='request'){
        final job=s.jobs.where((j)=>j['id']==item).firstOrNull;
        final expiry=job==null?null:DateTime.tryParse(text(job,'expires'));
        if(job==null||job['status']!='응답 대기'||expiry==null||!expiry.isAfter(DateTime.now()))throw const ServiceFailure(410,'마감되었거나 이미 처리된 배차 요청입니다.');
      }
      // Drop input controllers for the target so fresh server data is rendered.
      final prefix='$target/$item/';
      for(final key in fields.keys.where((key)=>key.startsWith(prefix)).toList()){fields.remove(key)?.dispose();}
      await api.call('notifications/read',{'notificationId':alert.id});
      if(!mounted)return;
      refresh(()=>alerts.removeWhere((a)=>a.id==alert.id));go(target,item:item);
    }catch(e){failure(e);}
    finally{if(mounted){refresh(()=>busy=false);drainPending();}}
  }

  Widget inbox()=>stack([button('새로고침',loadInbox,secondary:true),
    if(alerts.isEmpty)empty('알림이 없습니다.'),
    for(final alert in alerts)ListTile(title:Text(alert.title),
      leading:Icon(alert.read?Icons.notifications_none:Icons.notifications_active_outlined),
      onTap:busy?null:()=>openAlert(alert))]);
}
