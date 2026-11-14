import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:file_selector/file_selector.dart';
import 'package:geolocator/geolocator.dart';
import 'package:share_plus/share_plus.dart';
import 'package:audioplayers/audioplayers.dart';
import 'src/auth.dart';
import 'src/backend.dart';
import 'src/push.dart';
export 'src/backend.dart';
export 'src/push.dart';
export 'src/auth.dart';
part 'src/store.dart';
part 'src/domain.dart';
part 'src/shell.dart';
part 'src/admin.dart';
part 'src/driver.dart';
part 'src/files.dart';
part 'src/integration.dart';
part 'src/location.dart';

enum AppRole { admin, driver }
const developmentAccessEnabled = kDebugMode &&
    bool.fromEnvironment('ALLCHECK_DEV_ACCESS', defaultValue: true);
typedef Json = Map<String, dynamic>;
String text(Json row, String key, [String fallback = '']) => row[key]?.toString() ?? fallback;
List<Json> records(dynamic value) => value is List ? value.cast<Json>() : <Json>[];
List<String> strings(dynamic value) => value is List ? value.map((e) => e.toString()).toList() : <String>[];
String nowIso() => DateTime.now().toUtc().toIso8601String();
String uid() => '${DateTime.now().microsecondsSinceEpoch}-${math.Random.secure().nextInt(0x7fffffff)}';
Future<void> launchAllcheck(AppRole role, {
  FrontendBackend? backend,
  AppPush? push,
  DriverAuth? driverAuth,
  Future<Json?> Function(KakaoIdentity)? onKakaoAuthenticated,
}) async {
  WidgetsFlutterBinding.ensureInitialized();
  const baseUrl=String.fromEnvironment('API_BASE_URL');
  backend??=baseUrl.isEmpty?null:HttpFrontendBackend(baseUrl);
  push??=pushEnabled?FirebaseAppPush():null;
  if (role == AppRole.driver) KakaoDriverAuth.initialize();
  if(backend!=null){runApp(AllcheckApp(role:role,store:AppStore(role),backend:backend,push:push,driverAuth:driverAuth,onKakaoAuthenticated:onKakaoAuthenticated));return;}
  final preferences = SharedPreferencesAsync();
  final store = AppStore(role, save: (value) => preferences.setString('allcheck.flutter.${role.name}.v1', value));
  try { final value = await preferences.getString('allcheck.flutter.${role.name}.v1'); if (value != null) store.restore(value); }
  catch (_) { store.startupWarning = '저장 정보를 불러오지 못했습니다. 설정과 저장 공간을 확인해 주세요.'; }
  runApp(AllcheckApp(role: role, store: store, backend: backend, push: push, driverAuth: driverAuth,
    onKakaoAuthenticated: onKakaoAuthenticated));
}
class AllcheckApp extends StatelessWidget {
  const AllcheckApp({super.key, required this.role, required this.store, this.driverAuth, this.onKakaoAuthenticated, this.backend, this.push});
  final AppRole role;
  final AppStore store;
  final FrontendBackend? backend;
  final AppPush? push;
  final DriverAuth? driverAuth;
  /// Backend adapter: verify the Kakao token, then return the server account.
  /// Null means a new member; a returned account must contain server approval.
  final Future<Json?> Function(KakaoIdentity)? onKakaoAuthenticated;
  @override Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: role == AppRole.admin ? '관리자앱' : '기사앱',
    locale: const Locale('ko'),
    supportedLocales: const [Locale('ko'), Locale('en')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: ThemeData(useMaterial3: true, colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff55bbcc)),
      scaffoldBackgroundColor: Colors.white,
      textTheme: const TextTheme(bodyMedium: TextStyle(color: Color(0xff111d3d)), titleLarge: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
      appBarTheme: const AppBarTheme(backgroundColor: Colors.white, foregroundColor: Color(0xff111d3d), surfaceTintColor: Colors.transparent),
      inputDecorationTheme: InputDecorationTheme(border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: Color(0xffe6ecf2))), contentPadding: const EdgeInsets.all(14)),
      filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(minimumSize: const Size(0, 50), backgroundColor: const Color(0xff55bbcc), foregroundColor: Colors.white, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))))),
    home: WorkflowShell(role: role, store: store, backend: backend, push: push, driverAuth: driverAuth, onKakaoAuthenticated: onKakaoAuthenticated),
  );
}
