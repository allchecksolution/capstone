import 'dart:convert';

import 'package:allcheck_ui/allcheck_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

class _ReceptionServer {
  _ReceptionServer({required this.hasPhone});
  final bool hasPhone;
  int stage = 0;
  bool confirmed = false;
  Map<String, dynamic>? submitted;
  final from = DateTime.now().toUtc().add(const Duration(days: 1));

  http.Response response(Object body) => http.Response(
    jsonEncode(body),
    200,
    headers: {'content-type': 'application/json; charset=utf-8'},
  );

  Map<String, dynamic> detail() => {
    'receptionId': 'rec_auto',
    'version': confirmed ? 4 : stage + 1,
    'reviewStatus': confirmed ? 'CONFIRMED' : 'DRAFT',
    'reviewEditable': stage != 1 && !confirmed,
    'recordingStatus': stage == 0 ? 'NOT_AVAILABLE' : 'SUCCEEDED',
    'analysisStatus': stage == 0
        ? 'NOT_AVAILABLE'
        : stage == 1
        ? 'PROCESSING'
        : 'SUCCEEDED',
    'callerPhoneMasked': '*******9999',
    'transcript': stage == 2 ? '서버의 STT 원문' : null,
    'aiOriginal': stage == 2
        ? {
            'workType': 'BLOCKAGE',
            'symptoms': '최초 AI 증상',
            'address': '최초 AI 주소',
          }
        : null,
    'finalData': confirmed
        ? submitted!['finalData']
        : {
            if (stage > 0 && hasPhone) 'contactPhone': '01000000000',
            if (stage == 2) ...{
              'workType': 'WATERPROOFING',
              'symptoms': '서버 최신 증상',
              'address': '서버 최신 주소',
              'notes': '서버 최신 메모',
              'desiredVisitFrom': from.toIso8601String(),
              'desiredVisitTo': from
                  .add(const Duration(hours: 1))
                  .toIso8601String(),
            },
          },
  };

  Future<http.Response> handle(http.Request request) async {
    if (request.method == 'POST') {
      expect(request.url.path, '/api/v1/receptions/rec_auto/confirmation');
      submitted = jsonDecode(request.body) as Map<String, dynamic>;
      confirmed = true;
      return response({'receptionId': 'rec_auto', 'version': 4});
    }
    if (request.url.path == '/api/v1/receptions')
      return response({
        'items': [
          {
            'receptionId': 'rec_auto',
            'version': 1,
            'reviewStatus': 'DRAFT',
            'recordingStatus': 'NOT_AVAILABLE',
          },
        ],
        'page': {'page': 1, 'totalPages': 1},
      });
    return response(detail());
  }
}

Future<void> _tap(WidgetTester tester, String label) async {
  FocusManager.instance.primaryFocus?.unfocus();
  await tester.pumpAndSettle();
  await Scrollable.ensureVisible(
    tester.element(find.text(label)),
    alignment: 0.5,
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text(label));
  await tester.pumpAndSettle();
}

Finder _field(String key) => find.byKey(ValueKey('reception/rec_auto/$key'));
String _input(WidgetTester tester, String key) =>
    tester.widget<TextFormField>(_field(key)).controller!.text;

Future<void> _open(WidgetTester tester, _ReceptionServer server) async {
  final backend = SpringFrontendBackend(
    'https://api.example.com',
    client: MockClient(server.handle),
  )..setServiceSession('service-token');
  addTearDown(backend.close);
  await tester.pumpWidget(
    AllcheckApp(
      role: AppRole.admin,
      store: AppStore(AppRole.admin),
      backend: backend,
    ),
  );
  await tester.pumpAndSettle();
  await _tap(tester, 'rec_auto');
  expect(_input(tester, 'address'), isEmpty);
}

void main() {
  for (final hasPhone in [true, false]) {
    testWidgets(
      'untouched inputs refresh from finalData after analysis (phone=$hasPhone)',
      (tester) async {
        final server = _ReceptionServer(hasPhone: hasPhone);
        await _open(tester, server);
        if (hasPhone) {
          server.stage = 1;
          await _tap(tester, '상세 새로고침');
        }
        server.stage = 2;
        await _tap(tester, '상세 새로고침');
        expect(_input(tester, 'address'), '서버 최신 주소');
        expect(_input(tester, 'symptom'), '서버 최신 증상');
        expect(_input(tester, 'notes'), '서버 최신 메모');
        expect(_input(tester, 'phone'), hasPhone ? '01000000000' : '');
        final workType = tester.state<FormFieldState<String>>(
          find.byType(DropdownButtonFormField<String>),
        );
        expect(workType.value, '방수');
        expect(find.text('입력값을 최신 내용으로 바꾸기'), findsNothing);
        if (!hasPhone) await tester.enterText(_field('phone'), '01011111111');
        await _tap(tester, '검토 완료 · 최종 정보 저장');
        expect(server.submitted!['expectedVersion'], 3);
        final saved = server.submitted!['finalData'] as Map;
        expect(saved['workType'], 'WATERPROOFING');
        expect(saved['address'], '서버 최신 주소');
        expect(saved['symptoms'], '서버 최신 증상');
        expect(saved['contactPhone'], hasPhone ? '01000000000' : '01011111111');
        await tester.pumpWidget(const SizedBox.shrink());
      },
    );
  }

  testWidgets(
    'AI refresh fills untouched fields without overwriting a manual phone',
    (tester) async {
      final server = _ReceptionServer(hasPhone: true);
      await _open(tester, server);
      await tester.enterText(_field('phone'), '01022222222');
      server.stage = 2;
      await _tap(tester, '상세 새로고침');
      expect(_input(tester, 'phone'), '01022222222');
      expect(_input(tester, 'address'), '서버 최신 주소');
      expect(_input(tester, 'symptom'), '서버 최신 증상');
      expect(
        tester
            .state<FormFieldState<String>>(
              find.byType(DropdownButtonFormField<String>),
            )
            .value,
        '방수',
      );
      expect(find.text('입력값을 최신 내용으로 바꾸기'), findsOneWidget);
      expect(find.text('검토 완료 · 최종 정보 저장'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
