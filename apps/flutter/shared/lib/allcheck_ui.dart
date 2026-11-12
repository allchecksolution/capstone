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
part 'src/store.dart';
part 'src/domain.dart';
part 'src/shell.dart';
part 'src/admin.dart';
part 'src/driver.dart';
part 'src/files.dart';

enum AppRole { admin, driver }
typedef Json = Map<String, dynamic>;
String text(Json row, String key, [String fallback = '']) => row[key]?.toString() ?? fallback;
List<Json> records(dynamic value) => value is List ? value.cast<Json>() : <Json>[];
List<String> strings(dynamic value) => value is List ? value.map((e) => e.toString()).toList() : <String>[];
String nowIso() => DateTime.now().toUtc().toIso8601String();
String uid() => '${DateTime.now().microsecondsSinceEpoch}-${math.Random.secure().nextInt(0x7fffffff)}';
Future<void> launchAllcheck(AppRole role) async {
  WidgetsFlutterBinding.ensureInitialized();
  final preferences = SharedPreferencesAsync();
  final store = AppStore(role, save: (value) => preferences.setString('allcheck.flutter.${role.name}.v1', value));
  try { final value = await preferences.getString('allcheck.flutter.${role.name}.v1'); if (value != null) store.restore(value); }
  catch (_) { store.startupWarning = '저장 정보를 불러오지 못했습니다. 설정과 저장 공간을 확인해 주세요.'; }
  runApp(AllcheckApp(role: role, store: store));
}
class AllcheckApp extends StatelessWidget {
  const AllcheckApp({super.key, required this.role, required this.store});
  final AppRole role;
  final AppStore store;
  @override Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: role == AppRole.admin ? '올체크 관리자' : '올체크 기사',
    locale: const Locale('ko'),
    supportedLocales: const [Locale('ko'), Locale('en')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: ThemeData(useMaterial3: true, colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff55bbcc)),
      scaffoldBackgroundColor: Colors.white,
      textTheme: const TextTheme(bodyMedium: TextStyle(color: Color(0xff111d3d)), titleLarge: TextStyle(fontSize: 22, fontWeight: FontWeight.w700)),
      appBarTheme: const AppBarTheme(backgroundColor: Colors.white, foregroundColor: Color(0xff111d3d), surfaceTintColor: Colors.transparent),
      inputDecorationTheme: InputDecorationTheme(border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: const BorderSide(color: Color(0xffe6ecf2))), contentPadding: const EdgeInsets.all(14)),
      filledButtonTheme: FilledButtonThemeData(style: FilledButton.styleFrom(minimumSize: const Size(0, 50), backgroundColor: const Color(0xff55bbcc), foregroundColor: Colors.white, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))))),
    home: WorkflowShell(role: role, store: store),
  );
}
