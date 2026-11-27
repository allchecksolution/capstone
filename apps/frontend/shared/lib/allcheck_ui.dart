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
import 'src/business_document.dart';
import 'src/registration_date.dart';
import 'src/backend.dart';
import 'src/reception_backend.dart';
import 'src/recording_collection.dart';
import 'src/push.dart';
export 'src/backend.dart';
export 'src/reception_backend.dart';
export 'src/recording_collection.dart';
export 'src/push.dart';
export 'src/auth.dart';
export 'src/business_document.dart';
part 'src/store.dart';
part 'src/domain.dart';
part 'src/shell.dart';
part 'src/admin.dart';
part 'src/admin_accounts.dart';
part 'src/driver.dart';
part 'src/registration_fields.dart';
part 'src/business_ocr.dart';
part 'src/files.dart';
part 'src/integration.dart';
part 'src/receptions.dart';
part 'src/recording_settings.dart';
part 'src/connected_workflow.dart';
part 'src/location.dart';
part 'src/startup_logo.dart';

enum AppRole { admin, driver }
const developmentAccessEnabled = kDebugMode &&
    bool.fromEnvironment('ALLCHECK_DEV_ACCESS', defaultValue: true);
// Temporary review preview also works in installed release/profile builds.
const temporaryReviewAccessEnabled =
    bool.fromEnvironment('ALLCHECK_TEMP_REVIEW_ACCESS', defaultValue: true);
typedef Json = Map<String, dynamic>;
String text(Json row, String key, [String fallback = '']) => row[key]?.toString() ?? fallback;
List<Json> records(dynamic value) => value is List ? value.cast<Json>() : <Json>[];
List<String> strings(dynamic value) => value is List ? value.map((e) => e.toString()).toList() : <String>[];
String nowIso() => DateTime.now().toUtc().toIso8601String();
bool validOpeningDate(String value){
  if(!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(value))return false;
  final date=DateTime.tryParse(value);
  return date!=null&&date.year>0&&date.toIso8601String().substring(0,10)==value;
}
String uid() => '${DateTime.now().microsecondsSinceEpoch}-${math.Random.secure().nextInt(0x7fffffff)}';
Future<void> launchAllcheck(AppRole role, {
  FrontendBackend? backend,
  AppPush? push,
  DriverAuth? driverAuth,
  Future<Json?> Function(KakaoIdentity)? onKakaoAuthenticated,
}) async {
  WidgetsFlutterBinding.ensureInitialized();
  const baseUrl=String.fromEnvironment('API_BASE_URL');
  if (backend == null && baseUrl.isNotEmpty) {
    final url = baseUrl.replaceFirst(RegExp(r'/+$'), '');
    final apiUrl = Uri.parse(url).path.isEmpty ? '$url/api/v1' : url;
    backend = role == AppRole.driver
        ? DriverOnboardingBackend(apiUrl)
        : AdminRestBackend(apiUrl);
  }
  push??=pushEnabled?FirebaseAppPush():null;
  if (role == AppRole.driver) KakaoDriverAuth.initialize();
  if(backend!=null){runApp(AllcheckApp(role:role,store:AppStore(role),backend:backend,push:push,driverAuth:driverAuth,onKakaoAuthenticated:onKakaoAuthenticated,showStartupLogo:true));return;}
  final preferences = SharedPreferencesAsync();
  final store = AppStore(role, save: (value) => preferences.setString('allcheck.flutter.${role.name}.v1', value));
  try { final value = await preferences.getString('allcheck.flutter.${role.name}.v1'); if (value != null) store.restore(value); }
  catch (_) { store.startupWarning = '저장 정보를 불러오지 못했습니다. 설정과 저장 공간을 확인해 주세요.'; }
  runApp(AllcheckApp(role: role, store: store, backend: backend, push: push, driverAuth: driverAuth,
    onKakaoAuthenticated: onKakaoAuthenticated, showStartupLogo: true));
}
class AllcheckApp extends StatelessWidget {
  const AllcheckApp({super.key, required this.role, required this.store, this.driverAuth, this.onKakaoAuthenticated, this.backend, this.push, this.businessScanner, this.showStartupLogo = false});
  final BusinessDocumentScanner? businessScanner;
  final AppRole role;
  final AppStore store;
  final FrontendBackend? backend;
  final AppPush? push;
  final DriverAuth? driverAuth;
  final bool showStartupLogo;
  /// Backend adapter: verify the Kakao token, then return the server account.
  /// Null means a new member; a returned account must contain server approval.
  final Future<Json?> Function(KakaoIdentity)? onKakaoAuthenticated;
  @override Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: role == AppRole.admin ? '관리자앱' : '기사앱',
    locale: const Locale('ko'),
    supportedLocales: const [Locale('ko'), Locale('en')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    builder: (context, child) {
      final mediaQuery = MediaQuery.of(context);
      return MediaQuery(
        data: mediaQuery.copyWith(
          textScaler: _AllcheckTextScaler(mediaQuery.textScaler),
        ),
        child: showStartupLogo
            ? _StartupLogo(child: child!)
            : child!,
      );
    },
    theme: ThemeData(useMaterial3: true, colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff305bd6)),
      fontFamily: 'NanumSquareRound',
      package: 'allcheck_ui',
      scaffoldBackgroundColor: Colors.white,
      textTheme: const TextTheme(
        displayLarge: _headingTextStyle,
        displayMedium: _headingTextStyle,
        displaySmall: _headingTextStyle,
        headlineLarge: _headingTextStyle,
        headlineMedium: _headingTextStyle,
        headlineSmall: _headingTextStyle,
        titleLarge: TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
        titleMedium: _headingTextStyle,
        titleSmall: _bodyTextStyle,
        bodyLarge: _bodyTextStyle,
        bodyMedium: TextStyle(color: Color(0xff111d3d), fontWeight: FontWeight.w700),
        bodySmall: _bodyTextStyle,
        labelLarge: _bodyTextStyle,
        labelMedium: _bodyTextStyle,
        labelSmall: _bodyTextStyle,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.white,
        foregroundColor: Color(0xff17264b),
        surfaceTintColor: Colors.transparent,
        shadowColor: _outlineShadowColor,
        elevation: 0,
        scrolledUnderElevation: 1,
      ),
      navigationBarTheme: const NavigationBarThemeData(
        backgroundColor: Colors.white,
        indicatorColor: Color(0xffe1ebff),
        surfaceTintColor: Colors.transparent,
        shadowColor: _outlineShadowColor,
        elevation: 3,
      ),
      navigationRailTheme: const NavigationRailThemeData(
        backgroundColor: Colors.white,
        indicatorColor: Color(0xffe1ebff),
      ),
      inputDecorationTheme: const InputDecorationTheme(
        filled: true,
        fillColor: Color(0xfff7f8fa),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: Color(0xffe2e6ed)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: Color(0xffe2e6ed)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(14)),
          borderSide: BorderSide(color: Color(0xff305bd6), width: 2),
        ),
        contentPadding: EdgeInsets.all(14),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          backgroundColor: Colors.white,
          foregroundColor: const Color(0xff2348a3),
          side: const BorderSide(color: Color(0xffdce4f0)),
          shadowColor: _outlineShadowColor,
        ).copyWith(
          elevation: const WidgetStateProperty<double>.fromMap({
            WidgetState.disabled: 0,
            WidgetState.pressed: 0,
            WidgetState.any: 0,
          }),
        ),
      ),
      chipTheme: const ChipThemeData(
        backgroundColor: Color(0xfff4f6fb),
        selectedColor: Color(0xffdce8ff),
        side: BorderSide(color: Color(0xffe2e8f4)),
        elevation: 0,
        pressElevation: 0,
        shadowColor: _outlineShadowColor,
        selectedShadowColor: _outlineShadowColor,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 50),
          backgroundColor: const Color(0xff305bd6),
          foregroundColor: Colors.white,
          shadowColor: const Color(0x55305bd6),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        ).copyWith(
          elevation: WidgetStateProperty<double>.fromMap({
            WidgetState.disabled: 0,
            WidgetState.pressed: 1,
            WidgetState.hovered: 2,
            WidgetState.any: 0,
          }),
        ),
      ),
    ),
    home: WorkflowShell(role: role, store: store, backend: backend, push: push, driverAuth: driverAuth, onKakaoAuthenticated: onKakaoAuthenticated, businessScanner: businessScanner),
  );
}

const _outlineShadowColor = Color(0x301d3570);
const _headingTextStyle = TextStyle(fontWeight: FontWeight.w800);
const _bodyTextStyle = TextStyle(fontWeight: FontWeight.w700);

class _AllcheckTextScaler extends TextScaler {
  const _AllcheckTextScaler(this.systemScaler);

  final TextScaler systemScaler;
  static const double _sizeMultiplier = 1.15;

  @override
  double scale(double fontSize) => systemScaler.scale(fontSize) * _sizeMultiplier;

  @override
  double get textScaleFactor => scale(14) / 14;

  @override
  bool operator ==(Object other) =>
      other is _AllcheckTextScaler && other.systemScaler == systemScaler;

  @override
  int get hashCode => Object.hash(_AllcheckTextScaler, systemScaler);
}
