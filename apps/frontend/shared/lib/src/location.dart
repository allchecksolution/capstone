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
    if(mounted)await s.change(()=>s.data['location']='갱신됨 · ${position.timestamp.toLocal().toString().substring(11,19)}');
    if(mounted)refresh(()=>locationError='');
  }

  Future<void> startLocationUpdates()async{
    if(admin||!signedIn||s.data['availability']!='대기'){message('작업 가능 상태를 대기로 변경해 주세요.');return;}
    await platformTask(()async{
      await locationPermission();
      await locationSubscription?.cancel();
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
        if(!signedIn||s.data['availability']!='대기'){await locationSubscription?.cancel();locationSubscription=null;return;}
        if(sendingLocation)return;sendingLocation=true;
        try{await sendPosition(position);}catch(e){if(mounted)refresh(()=>locationError='위치를 갱신하지 못했습니다. 위치 권한과 연결 상태를 확인해 주세요.');}
        finally{sendingLocation=false;}
      },onError:(Object error){if(mounted)refresh(()=>locationError='위치 갱신이 중단되었습니다. 권한을 확인하고 다시 켜주세요.');});
      message('위치 갱신을 시작했습니다. 휴식 또는 로그아웃 시 중단됩니다.');
    });
  }
}
