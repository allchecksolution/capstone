import 'dart:async';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

const pushEnabled = bool.fromEnvironment('ALLCHECK_PUSH_ENABLED', defaultValue: false);

/// Only identifiers go into push data. Fetch actual content after authentication.
class AppAlert {
  const AppAlert({required this.id, required this.type, required this.resourceId,
    required this.role, this.read = false});
  final String id, type, resourceId, role;
  final bool read;
  static AppAlert? parse(Map<String, dynamic> data) {
    final id = data['notificationId'], type = data['type'], resource = data['resourceId'], role = data['role'];
    if ([id, type, resource, role].any((v) => v is! String || v.isEmpty || v.length > 256)) return null;
    const types = {'analysis_completed', 'analysis_failed', 'dispatch_requested',
      'dispatch_updated', 'work_updated', 'chat_received', 'driver_approval', 'dispatch_cancelled'};
    if (!types.contains(type) || !['admin', 'driver'].contains(role)) return null;
    return AppAlert(id: id as String, type: type as String, resourceId: resource as String,
      role: role as String, read: data['read'] == true);
  }
  Map<String, dynamic> toJson() => {'notificationId': id, 'type': type,
    'resourceId': resourceId, 'role': role, 'read': read};
  String get title => switch(type) {
    'analysis_completed' => '분석이 완료되었습니다. 접수 내용을 확인해 주세요.',
    'analysis_failed' => '접수 정보를 직접 확인해 주세요.',
    'dispatch_requested' => '새로운 배차 요청이 도착했습니다.',
    'dispatch_cancelled' => '배차가 취소되었습니다.',
    'dispatch_updated' => '배차 상태가 변경되었습니다.',
    'work_updated' => '작업 상태가 변경되었습니다.',
    'chat_received' => '새 메시지가 도착했습니다.',
    'driver_approval' => '가입 심사 상태가 변경되었습니다.',
    _ => '새 알림이 도착했습니다.',
  };
}

@pragma('vm:entry-point')
Future<void> allcheckBackgroundMessage(RemoteMessage message) async {
  await Firebase.initializeApp();
  // Background isolate must not navigate or persist customer/driver data.
  // FCM notification+data is displayed by the OS; inbox is read from server.
}

abstract interface class AppPush {
  Stream<AppAlert> get received;
  Stream<AppAlert> get opened;
  Stream<String> get tokens;
  Future<AppAlert?> start();
  Future<String> requestPermission();
  Future<String?> token();
  Future<void> deleteToken();
  Future<void> dispose();
}

class FirebaseAppPush implements AppPush {
  final _received = StreamController<AppAlert>.broadcast();
  final _opened = StreamController<AppAlert>.broadcast();
  final _tokens = StreamController<String>.broadcast();
  final _subscriptions = <StreamSubscription<dynamic>>[];
  bool _ready = false;bool _disposed=false;
  @override Stream<AppAlert> get received => _received.stream;
  @override Stream<AppAlert> get opened => _opened.stream;
  @override Stream<String> get tokens => _tokens.stream;
  void _emit(RemoteMessage m, StreamController<AppAlert> sink) {
    final alert = AppAlert.parse(m.data);
    if (alert != null && !sink.isClosed) sink.add(alert);
  }
  @override Future<AppAlert?> start() async {
    if (!pushEnabled || kIsWeb || ![TargetPlatform.android, TargetPlatform.iOS].contains(defaultTargetPlatform)) return null;
    await Firebase.initializeApp();
    if(_disposed)return null;
    FirebaseMessaging.onBackgroundMessage(allcheckBackgroundMessage);
    await FirebaseMessaging.instance.setAutoInitEnabled(false);
    // Foreground uses the in-app banner, preventing duplicate OS alerts.
    await FirebaseMessaging.instance.setForegroundNotificationPresentationOptions(alert: false, badge: false, sound: false);
    if(_disposed)return null;
    _subscriptions.add(FirebaseMessaging.onMessage.listen((m) => _emit(m, _received)));
    _subscriptions.add(FirebaseMessaging.onMessageOpenedApp.listen((m) => _emit(m, _opened)));
    _subscriptions.add(FirebaseMessaging.instance.onTokenRefresh.listen(_tokens.add,onError:(Object _) {}));
    _ready = true;
    final initial = await FirebaseMessaging.instance.getInitialMessage();
    return initial == null ? null : AppAlert.parse(initial.data);
  }
  @override Future<String> requestPermission() async {
    if (!_ready) return 'unavailable';
    final result = await FirebaseMessaging.instance.requestPermission(alert: true, badge: true, sound: true);
    return result.authorizationStatus.name;
  }
  @override Future<String?> token() async {
    if (!_ready) return null;
    final settings = await FirebaseMessaging.instance.getNotificationSettings();
    if (![AuthorizationStatus.authorized, AuthorizationStatus.provisional].contains(settings.authorizationStatus)) return null;
    if (defaultTargetPlatform == TargetPlatform.iOS && await FirebaseMessaging.instance.getAPNSToken() == null) return null;
    return FirebaseMessaging.instance.getToken();
  }
  @override Future<void> deleteToken() async { if (_ready) await FirebaseMessaging.instance.deleteToken(); }
  @override Future<void> dispose() async {
    _disposed=true;
    for (final subscription in _subscriptions) { await subscription.cancel(); }
    await _received.close(); await _opened.close(); await _tokens.close();
  }
}
