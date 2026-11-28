part of '../allcheck_ui.dart';

extension LocationScreens on _WorkflowShellState {
  Future<void> locationPermission()async{
    if(!await Geolocator.isLocationServiceEnabled())throw StateError('기기의 위치 서비스를 켜주세요.');
    var permission=await Geolocator.checkPermission();
    if(permission==LocationPermission.denied)permission=await Geolocator.requestPermission();
    if(permission==LocationPermission.denied||permission==LocationPermission.deniedForever)throw StateError('위치 권한이 필요합니다. 기기 설정에서 허용해 주세요.');
  }

  Future<void> sendPosition(Position position)async{
    if(!mounted||!signedIn||admin||s.account?['status']!='활성')return;
    final age=DateTime.now().difference(position.timestamp).inSeconds;
    if(age<0||age>120)throw StateError('위치가 오래되었습니다. 다시 확인해 주세요.');
    if(!developmentSession)await api.call('drivers/location',{
      'latitude':position.latitude,'longitude':position.longitude,
      'accuracy':position.accuracy,'recordedAt':position.timestamp.toUtc().toIso8601String()});
    lastLocationSentAt=DateTime.now();
    if(mounted)await s.change(()=>s.data['location']='갱신됨 · ${position.timestamp.toLocal().toString().substring(11,19)}');
    if(mounted)refresh(()=>locationError='');
  }

  Future<void> stopLocationUpdates()async{
    locationHeartbeat?.cancel();locationHeartbeat=null;
    final subscription=locationSubscription;locationSubscription=null;
    await subscription?.cancel();
  }

  /// The server only treats a location as dispatchable for a few minutes, so a
  /// stationary driver must still report periodically while READY.
  Future<void> sendCurrentPosition()async{
    if(sendingLocation||!signedIn||s.data['availability']!='대기')return;
    sendingLocation=true;
    try{
      final position=await Geolocator.getCurrentPosition(locationSettings:const LocationSettings(accuracy:LocationAccuracy.medium,timeLimit:Duration(seconds:15)));
      await sendPosition(position);
    }catch(e){
      if(e is ServiceFailure&&e.status==401){failure(e);return;}
      if(mounted)refresh(()=>locationError='위치를 갱신하지 못했습니다. 위치 권한과 연결 상태를 확인해 주세요.');
    }finally{sendingLocation=false;}
  }

  /// Restarts reporting after sign-in or app restart without prompting.
  Future<void> resumeLocationUpdates()async{
    if(admin||developmentSession||!signedIn||locationSubscription!=null)return;
    if(s.data['availability']!='대기'||s.account?['status']!='활성')return;
    try{
      final permission=await Geolocator.checkPermission();
      if(permission!=LocationPermission.always&&permission!=LocationPermission.whileInUse){
        if(mounted)refresh(()=>locationError='대기 상태이지만 위치 권한이 없어 배차 후보가 될 수 없습니다. 위치 갱신을 다시 켜주세요.');
        return;
      }
      await startLocationUpdates(quiet:true);
    }catch(_){}
  }

  Future<void> startLocationUpdates({bool quiet=false})async{
    if(admin||!signedIn||s.data['availability']!='대기'){if(!quiet)message('작업 가능 상태를 대기로 변경해 주세요.');return;}
    Future<void> start()async{
      await locationPermission();
      await stopLocationUpdates();
      LocationSettings settings=const LocationSettings(accuracy:LocationAccuracy.medium,distanceFilter:50);
      if(!kIsWeb&&defaultTargetPlatform==TargetPlatform.android){
        settings=AndroidSettings(accuracy:LocationAccuracy.medium,distanceFilter:50,
          intervalDuration:const Duration(seconds:30),
          foregroundNotificationConfig:const ForegroundNotificationConfig(
            notificationTitle:'기사앱',notificationText:'대기 중 배차를 위해 위치를 갱신하고 있습니다.',enableWakeLock:false));
      }else if(!kIsWeb&&defaultTargetPlatform==TargetPlatform.iOS){
        settings=AppleSettings(accuracy:LocationAccuracy.medium,distanceFilter:50,
          pauseLocationUpdatesAutomatically:true,showBackgroundLocationIndicator:true,
          allowBackgroundLocationUpdates:true);
      }
      locationSubscription=Geolocator.getPositionStream(locationSettings:settings).listen((position)async{
        if(!signedIn||s.data['availability']!='대기'){await stopLocationUpdates();return;}
        if(sendingLocation)return;sendingLocation=true;
        try{await sendPosition(position);}catch(e){if(mounted)refresh(()=>locationError='위치를 갱신하지 못했습니다. 위치 권한과 연결 상태를 확인해 주세요.');}
        finally{sendingLocation=false;}
      },onError:(Object error){if(mounted)refresh(()=>locationError='위치 갱신이 중단되었습니다. 권한을 확인하고 다시 켜주세요.');});
      if(!developmentSession){
        locationHeartbeat=Timer.periodic(const Duration(minutes:1),(_){
          final last=lastLocationSentAt;
          if(last==null||DateTime.now().difference(last)>=const Duration(minutes:2))unawaited(sendCurrentPosition());
        });
        await sendCurrentPosition();
      }
      if(!quiet)message('위치 갱신을 시작했습니다. 휴식 또는 로그아웃 시 중단됩니다.');
    }
    if(quiet){try{await start();}catch(_){if(mounted)refresh(()=>locationError='위치 갱신을 시작하지 못했습니다. 위치 권한을 확인해 주세요.');}return;}
    await platformTask(start);
  }
}
