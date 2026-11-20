part of '../allcheck_ui.dart';

extension _ReceptionScreens on _WorkflowShellState {
  bool get springReceptions =>
      widget.backend is SpringFrontendBackend && !developmentSession;

  Future<void> loadReceptions({int? pageNumber}) async {
    if (!springReceptions || busy || !signedIn) return;
    final route = page;
    final resource = id;
    final generation = stateVersion;
    refresh(() => busy = true);
    try {
      final result = route == 'reception'
          ? await api.call('receptions/detail', {'id': resource})
          : await api.call('receptions/list', {
              'page': pageNumber ?? receptionPage['page'] ?? 1,
            });
      if (mounted &&
          signedIn &&
          route == page &&
          resource == id &&
          generation == stateVersion) {
        await applyServerState(result);
      }
    } catch (e) {
      failure(e);
    } finally {
      if (mounted) refresh(() => busy = false);
    }
  }

  Widget springReceptionList() => stack([
    button('상담 접수 만들기', () async {
      if (await command('receptions/create', {}, () {})) {
        go('reception', item: lastResourceId);
      }
    }),
    button('목록 새로고침', () => loadReceptions(), secondary: true),
    for (final c in s.consultations)
      box([
        pill(receptionStatus(c)),
        menu(
          text(c, 'id'),
          'reception',
          item: text(c, 'id'),
          subtitle:
              '${text(c, 'callerPhoneMasked', '발신번호 없음')} · ${text(c, 'receivedAt')}',
        ),
      ]),
    if (s.consultations.isEmpty) empty('상담 접수가 없습니다.'),
    if (receptionPage.isNotEmpty)
      Row(
        children: [
          if ((receptionPage['page'] as int? ?? 1) > 1)
            TextButton(
              onPressed: busy
                  ? null
                  : () => loadReceptions(
                      pageNumber: (receptionPage['page'] as int) - 1,
                    ),
              child: const Text('이전 페이지'),
            ),
          Expanded(
            child: Center(
              child: Text(
                '${receptionPage['page']} / ${math.max(1, receptionPage['totalPages'] as int? ?? 1)} 페이지',
              ),
            ),
          ),
          if ((receptionPage['page'] as int? ?? 1) <
              (receptionPage['totalPages'] as int? ?? 1))
            TextButton(
              onPressed: busy
                  ? null
                  : () => loadReceptions(
                      pageNumber: (receptionPage['page'] as int) + 1,
                    ),
              child: const Text('다음 페이지'),
            ),
        ],
      ),
  ]);

  Widget receptionVisitField(String key, String label, String initial) {
    final input = controller(key, initial);
    final parsed = DateTime.tryParse(input.text)?.toLocal();
    return OutlinedButton(
      onPressed: busy
          ? null
          : () async {
              final date = await showDatePicker(
                context: context,
                initialDate: parsed ?? DateTime.now(),
                firstDate: DateTime(2000),
                lastDate: DateTime(2100),
              );
              if (date == null || !mounted) return;
              final time = await showTimePicker(
                context: context,
                initialTime: parsed == null
                    ? TimeOfDay.now()
                    : TimeOfDay.fromDateTime(parsed),
              );
              if (time != null && mounted) {
                refresh(() {
                  input.text = DateTime(
                    date.year,
                    date.month,
                    date.day,
                    time.hour,
                    time.minute,
                  ).toUtc().toIso8601String();
                });
              }
            },
      child: Text(
        '$label * · ${parsed == null ? '날짜·시간 선택' : parsed.toString().substring(0, 16)}',
      ),
    );
  }

  Widget springReceptionDetail() {
    final c = s.consultations.where((row) => row['id'] == id).firstOrNull;
    if (c == null || c['reviewEditable'] == null) {
      return stack([
        small('접수 상세 정보를 불러와 주세요.'),
        button('상세 새로고침', () => loadReceptions(), secondary: true),
      ]);
    }
    final editable = c['reviewEditable'] == true;
    return form([
      box([
        pill(receptionStatus(c)),
        kv('통화 상대 번호', c['callerPhoneMasked']),
        if (c['stt'] != null) kv('상담 내용', c['stt']),
        if (c['analysis'] != null)
          kv(
            '최초 분석 결과',
            const JsonEncoder.withIndent('  ').convert(c['analysis']),
          ),
        if (c['status'] == '분석 실패')
          notice('분석 처리에 실패했습니다. 수정이 가능해지면 접수 정보를 직접 입력해 주세요.', error: true),
        if (!editable) small('현재 접수 상태에서는 검토 정보를 수정할 수 없습니다.'),
      ]),
      button('상세 새로고침', () => loadReceptions(), secondary: true),
      if (c['recordingStatus'] == 'SUCCEEDED' && c['recordingFileId'] != null)
        button('녹음 원본 재생', () => playReceptionRecording(c), secondary: true),
      if (c['recordingFileId'] == null || c['recordingStatus'] == 'PENDING')
        button(
          'WAV 녹음 선택 · 업로드',
          () => uploadReceptionRecording(c),
          secondary: true,
        ),
      if (editable) ...[
        dropdown(
          'type',
          '작업 유형 *',
          SpringFrontendBackend.workTypes.values.toList(),
          initial: text(c, 'type'),
          required: true,
        ),
        field('phone', '고객 연락처', initial: text(c, 'phone')),
        field('address', '방문 주소', initial: text(c, 'address')),
        field('symptom', '증상', initial: text(c, 'symptom'), lines: true),
        receptionVisitField(
          'desiredVisitFrom',
          '방문 희망 시작',
          text(c, 'desiredVisitFrom'),
        ),
        receptionVisitField(
          'desiredVisitTo',
          '방문 희망 종료',
          text(c, 'desiredVisitTo'),
        ),
        field(
          'notes',
          '추가 요청 및 메모',
          initial: text(c, 'notes'),
          required: false,
          lines: true,
        ),
        button('검토 완료 · 최종 정보 저장', () async {
          if (!(formKey.currentState?.validate() ?? false)) return;
          if (await command('receptions/review', {
            'id': id,
            'fields': values([
              'type',
              'phone',
              'address',
              'symptom',
              'desiredVisitFrom',
              'desiredVisitTo',
              'notes',
            ]),
          }, () {})) {
            message('검토 정보를 저장했습니다.');
          }
        }),
      ] else
        box([
          for (final key in [
            'type',
            'phone',
            'address',
            'symptom',
            'desiredVisitFrom',
            'desiredVisitTo',
            'notes',
          ])
            kv(
              {
                'type': '작업 유형',
                'phone': '고객 연락처',
                'address': '주소',
                'symptom': '증상',
                'desiredVisitFrom': '방문 시작',
                'desiredVisitTo': '방문 종료',
                'notes': '메모',
              }[key]!,
              c[key],
            ),
        ]),
      if (c['reviewed'] == true && c['jobId'] == null)
        button('검토 확정 취소 · 다시 수정', () async {
          if (await confirm('검토 확정을 취소하고 다시 수정할까요?')) {
            await command('receptions/reopen', {'id': id}, () {});
          }
        }, secondary: true),
      if (c['jobId'] != null) kv('등록된 의뢰 번호', c['jobId']),
    ]);
  }

  Future<void> uploadReceptionRecording(Json c) => platformTask(() async {
    const type = XTypeGroup(
      label: 'WAV 녹음',
      extensions: ['wav'],
      mimeTypes: ['audio/wav', 'audio/x-wav'],
    );
    final file = await openFile(acceptedTypeGroups: const [type]);
    if (file == null || !mounted) return;
    final bytes = await file.readAsBytes();
    final result = await api.call('receptions/recording-upload', {
      'id': c['id'],
      'expectedVersion': c['version'],
      'recordingFileId': c['recordingFileId'],
      'requestId': uid(),
      'originalFilename': file.name,
      'bytes': bytes,
    });
    if (!mounted || !signedIn) return;
    await applyServerState(result);
    message('녹음 업로드 검증을 요청했습니다. 분석 상태는 새로고침으로 확인해 주세요.');
  });

  Future<void> playReceptionRecording(Json c) => platformTask(() async {
    final result = await api.call('receptions/recording-access', {
      'id': c['id'],
      'recordingFileId': c['recordingFileId'],
    });
    if (!mounted || !signedIn) return;
    final url = text(result, 'url');
    if (Uri.tryParse(url)?.scheme != 'https') {
      throw const ServiceFailure(0, '녹음 재생 주소를 확인하지 못했습니다.');
    }
    // Short-lived URL stays in this dialog and is never persisted in AppStore.
    await showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('녹음 원본'),
        content: _AudioPreview(url: url),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('닫기'),
          ),
        ],
      ),
    );
  });
}
