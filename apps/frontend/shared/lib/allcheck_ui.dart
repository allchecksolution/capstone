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
part 'src/business_ocr.dart';
part 'src/files.dart';
part 'src/integration.dart';
part 'src/receptions.dart';
part 'src/recording_settings.dart';
part 'src/connected_workflow.dart';
part 'src/location.dart';

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
  if(backend!=null){runApp(AllcheckApp(role:role,store:AppStore(role),backend:backend,push:push,driverAuth:driverAuth,onKakaoAuthenticated:onKakaoAuthenticated));return;}
  final preferences = SharedPreferencesAsync();
  final store = AppStore(role, save: (value) => preferences.setString('allcheck.flutter.${role.name}.v1', value));
  try { final value = await preferences.getString('allcheck.flutter.${role.name}.v1'); if (value != null) store.restore(value); }
  catch (_) { store.startupWarning = '저장 정보를 불러오지 못했습니다. 설정과 저장 공간을 확인해 주세요.'; }
  runApp(AllcheckApp(role: role, store: store, backend: backend, push: push, driverAuth: driverAuth,
    onKakaoAuthenticated: onKakaoAuthenticated));
}
class AllcheckApp extends StatelessWidget {
  const AllcheckApp({super.key, required this.role, required this.store, this.driverAuth, this.onKakaoAuthenticated, this.backend, this.push, this.businessScanner});
  final BusinessDocumentScanner? businessScanner;
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
    builder: (context, child) {
      final mediaQuery = MediaQuery.of(context);
      return MediaQuery(
        data: mediaQuery.copyWith(
          textScaler: _AllcheckTextScaler(mediaQuery.textScaler),
        ),
        child: child!,
      );
    },
    theme: ThemeData(useMaterial3: true, colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff0099d6)),
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
      appBarTheme: const AppBarTheme(backgroundColor: Colors.white, foregroundColor: Color(0xff111d3d), surfaceTintColor: Colors.transparent),
      inputDecorationTheme: const InputDecorationTheme(
        border: _ShadowedInputBorder(
          borderRadius: BorderRadius.all(Radius.circular(10)),
          borderSide: BorderSide(color: Color(0xffe6ecf2)),
        ),
        contentPadding: EdgeInsets.all(14),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          backgroundColor: Colors.white,
          shadowColor: _outlineShadowColor,
        ).copyWith(
          elevation: const WidgetStateProperty<double>.fromMap({
            WidgetState.disabled: 0,
            WidgetState.pressed: 0,
            WidgetState.any: 2,
          }),
        ),
      ),
      chipTheme: const ChipThemeData(
        elevation: 2,
        pressElevation: 0,
        shadowColor: _outlineShadowColor,
        selectedShadowColor: _outlineShadowColor,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size(0, 50),
          backgroundColor: const Color(0xff0099d6),
          foregroundColor: Colors.white,
          shadowColor: const Color(0x59111d3d),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        ).copyWith(
          elevation: WidgetStateProperty<double>.fromMap({
            WidgetState.disabled: 0,
            WidgetState.pressed: 1,
            WidgetState.hovered: 5,
            WidgetState.any: 3,
          }),
        ),
      ),
    ),
    home: WorkflowShell(role: role, store: store, backend: backend, push: push, driverAuth: driverAuth, onKakaoAuthenticated: onKakaoAuthenticated, businessScanner: businessScanner),
  );
}

const _outlineShadowColor = Color(0x40111d3d);
const _headingTextStyle = TextStyle(fontWeight: FontWeight.w800);
const _bodyTextStyle = TextStyle(fontWeight: FontWeight.w700);

class _ShadowedInputBorder extends OutlineInputBorder {
  const _ShadowedInputBorder({
    required super.borderRadius,
    required super.borderSide,
    super.gapPadding,
  });

  @override
  _ShadowedInputBorder copyWith({
    BorderSide? borderSide,
    BorderRadius? borderRadius,
    double? gapPadding,
  }) => _ShadowedInputBorder(
    borderSide: borderSide ?? this.borderSide,
    borderRadius: borderRadius ?? this.borderRadius,
    gapPadding: gapPadding ?? this.gapPadding,
  );

  @override
  ShapeBorder? lerpFrom(ShapeBorder? a, double t) {
    final border = super.lerpFrom(a, t);
    return border is OutlineInputBorder
        ? copyWith(
            borderSide: border.borderSide,
            borderRadius: border.borderRadius,
            gapPadding: border.gapPadding,
          )
        : border;
  }

  @override
  void paint(
    Canvas canvas,
    Rect rect, {
    double? gapStart,
    double gapExtent = 0,
    double gapPercentage = 0,
    TextDirection? textDirection,
  }) {
    if (borderSide.style != BorderStyle.none) {
      final outline = getOuterPath(rect, textDirection: textDirection);
      final outside = Path()
        ..fillType = PathFillType.evenOdd
        ..addRect(rect.inflate(12))
        ..addPath(outline, Offset.zero);
      canvas.save();
      canvas.clipPath(outside);
      canvas.drawShadow(outline, _outlineShadowColor, 2, false);
      canvas.restore();
    }
    super.paint(
      canvas,
      rect,
      gapStart: gapStart,
      gapExtent: gapExtent,
      gapPercentage: gapPercentage,
      textDirection: textDirection,
    );
  }
}

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
