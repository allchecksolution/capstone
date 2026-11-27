import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

/// Local OCR supplies suggestions, never business verification or consent.
class BusinessDocumentFields {
  BusinessDocumentFields(this.values);
  final Map<String, String> values;
  static const labels = {
    'businessNumber': '사업자등록번호',
    'company': '사업체명',
    'owner': '대표자명',
    'companyAddress': '사업장 주소',
    'businessType': '업태',
    'businessItem': '종목',
  };

  static BusinessDocumentFields parse(String text) {
    final result = <String, String>{};
    final labels = RegExp(
      r'(사업자\s*등록\s*번호|등록\s*번호|법\s*인\s*명\s*\(\s*단\s*체\s*명\s*\)|법\s*인\s*명|상\s*호\s*\(\s*법\s*인\s*명\s*\)|상\s*호|대표\s*자\s*(?:성\s*명|명)?|성\s*명|사업장\s*소재지|사업장\s*주소|업\s*태|종\s*목|개업\s*연월일|생년월일|사업의\s*종류|본점\s*소재지|발급\s*사유|사업자\s*단위\s*과세|공동\s*사업자)',
    );
    String? key(String label) {
      final compact = label.replaceAll(RegExp(r'\s'), '');
      if (compact.endsWith('등록번호')) return 'businessNumber';
      if (compact.startsWith('상호') || compact.startsWith('법인명')) {
        return 'company';
      }
      if (compact.startsWith('대표자') || compact == '성명') return 'owner';
      if (compact.startsWith('사업장')) return 'companyAddress';
      if (compact == '업태') return 'businessType';
      if (compact == '종목') return 'businessItem';
      return null;
    }

    final lines = text
        .replaceAll('\r', '')
        .split('\n')
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();
    for (var i = 0; i < lines.length; i++) {
      final matches = labels.allMatches(lines[i]).toList();
      for (var j = 0; j < matches.length; j++) {
        final field = key(matches[j].group(0)!);
        if (field == null) continue;
        var value = lines[i]
            .substring(
              matches[j].end,
              j + 1 < matches.length ? matches[j + 1].start : null,
            )
            .replaceFirst(RegExp(r'^[\s:：·.\-]+'), '')
            .trim();
        if (value.isEmpty &&
            i + 1 < lines.length &&
            !labels.hasMatch(lines[i + 1])) {
          value = lines[i + 1];
        }
        if (field == 'businessNumber') {
          final number = RegExp(
            r'(\d{3})[\s\-‐‑–]*(\d{2})[\s\-‐‑–]*(\d{5})(?!\d)',
          ).firstMatch(value);
          if (number == null) continue;
          value = '${number[1]}-${number[2]}-${number[3]}';
        }
        if (value.isNotEmpty &&
            value.length <= (field == 'companyAddress' ? 300 : 150)) {
          result.putIfAbsent(field, () => value);
        }
      }
    }
    // A clearly formatted number may be recognised without its label.
    final number = RegExp(
      r'(?<!\d)(\d{3})\s*[-‐‑–]\s*(\d{2})\s*[-‐‑–]\s*(\d{5})(?!\d)',
    ).firstMatch(text);
    if (number != null) {
      result.putIfAbsent(
        'businessNumber',
        () => '${number[1]}-${number[2]}-${number[3]}',
      );
    }
    return BusinessDocumentFields(result);
  }
}

class BusinessDocumentScanner {
  static const _channel = MethodChannel('allcheck/platform');
  bool get supported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  Future<BusinessDocumentFields?> scan({required bool camera}) async {
    if (!supported) throw StateError('사업자등록증 자동 인식은 Android 앱에서 지원합니다.');
    try {
      final picker = ImagePicker();
      final lost = await picker.retrieveLostData();
      if (lost.exception != null) {
        throw StateError('이전 사진을 복구하지 못했습니다. 다시 선택해 주세요.');
      }
      final recovered = lost.files;
      final image = recovered?.isNotEmpty == true
          ? recovered!.first
          : await picker.pickImage(
              source: camera ? ImageSource.camera : ImageSource.gallery,
              maxWidth: 2600,
              imageQuality: 95,
            );
      if (image == null) return null;
      final text = await _channel.invokeMethod<String>(
        'recognizeBusinessDocument',
        {'path': image.path},
      );
      return BusinessDocumentFields.parse(text ?? '');
    } on PlatformException {
      throw StateError('사업자등록증을 읽지 못했습니다. 밝고 선명하게 다시 촬영하거나 직접 입력해 주세요.');
    } on MissingPluginException {
      throw StateError('자동 인식을 사용할 수 없습니다. 사업자 정보를 직접 입력해 주세요.');
    }
  }
}
