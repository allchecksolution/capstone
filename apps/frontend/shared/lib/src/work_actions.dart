part of '../allcheck_ui.dart';

class _WorkActions extends StatefulWidget {
  const _WorkActions({
    super.key,
    required this.api,
    required this.work,
    required this.onChanged,
  });
  final FrontendBackend api;
  final Json work;
  final VoidCallback onChanged;
  @override
  State<_WorkActions> createState() => _WorkActionsState();
}

class _WorkActionsState extends State<_WorkActions> {
  final reason = TextEditingController(),
      description = TextEditingController(),
      diagnosis = TextEditingController(),
      cost = TextEditingController();
  final photos = {'BEFORE': <String, String>{}, 'AFTER': <String, String>{}};
  final keys = <String, String>{};
  String cancellation = 'OTHER', error = '';
  bool busy = false;

  @override
  void dispose() {
    for (final c in [reason, description, diagnosis, cost]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> run(Future<void> Function() action) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = '';
    });
    try {
      await action();
    } catch (e) {
      if (mounted)
        setState(
          () => error = e is ServiceFailure
              ? e.message
              : '처리하지 못했습니다. 다시 시도해 주세요.',
        );
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> command(String op, Json values) => run(() async {
    final body = {'expectedVersion': widget.work['version'], ...values};
    final fingerprint = '$op/${jsonEncode(body)}';
    try {
      await widget.api.call(op, {
        'workId': widget.work['workId'],
        'body': body,
        'requestId': keys.putIfAbsent(fingerprint, uid),
      });
    } on ServiceFailure catch (e) {
      if (mounted && e.status == 409) widget.onChanged();
      rethrow;
    }
    if (mounted) widget.onChanged();
  });

  Future<void> addPhotos(String phase) => run(() async {
    const type = XTypeGroup(
      label: '사진',
      extensions: ['jpg', 'jpeg', 'png'],
      mimeTypes: ['image/jpeg', 'image/png'],
      uniformTypeIdentifiers: ['public.jpeg', 'public.png'],
    );
    final files = await openFiles(acceptedTypeGroups: const [type]);
    final selected = photos[phase]!;
    if (selected.length + files.length > 5)
      throw const ServiceFailure(400, '작업 전후 사진은 각각 최대 5장입니다.');
    for (final file in files) {
      if (!mounted) return;
      final size = await file.length();
      if (size <= 0 || size > 10000000)
        throw const ServiceFailure(400, '사진은 장당 10MB 이하로 선택해 주세요.');
      final bytes = await file.readAsBytes();
      final hash = sha256.convert(bytes).toString();
      final mime = bytes.length >= 2 && bytes[0] == 0xff && bytes[1] == 0xd8
          ? 'image/jpeg'
          : bytes.length >= 8 && bytes[0] == 137 && bytes[1] == 80
          ? 'image/png'
          : null;
      if (mime == null)
        throw const ServiceFailure(400, 'JPEG 또는 PNG 사진을 선택해 주세요.');
      if (!mounted) return;
      final result = await widget.api.call('work/photo', {
        'workId': widget.work['workId'],
        'phase': phase,
        'bytes': bytes,
        'contentType': mime,
        'filename': file.name,
        'requestId': keys.putIfAbsent('$phase/$hash/${file.name}', uid),
      });
      if (!mounted) return;
      setState(() => selected[result['photoFileId'] as String] = file.name);
    }
  });

  Widget input(
    TextEditingController controller,
    String label, {
    bool number = false,
  }) => TextField(
    controller: controller,
    enabled: !busy,
    keyboardType: number ? TextInputType.number : TextInputType.text,
    decoration: InputDecoration(labelText: label),
  );

  @override
  Widget build(BuildContext context) {
    final next = const {
      'NOT_STARTED': 'EN_ROUTE',
      'EN_ROUTE': 'ARRIVED',
      'ARRIVED': 'IN_PROGRESS',
      'IN_PROGRESS': 'REVISIT_REQUIRED',
      'REVISIT_REQUIRED': 'EN_ROUTE',
    }[widget.work['workStatus']];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.work['canTransition'] == true) ...[
          if (next == 'REVISIT_REQUIRED') input(reason, '재방문 또는 취소 사유'),
          if (next != null)
            FilledButton(
              onPressed: busy
                  ? null
                  : () {
                      if (next == 'REVISIT_REQUIRED' &&
                          reason.text.trim().isEmpty) {
                        setState(() => error = '재방문 사유를 입력해 주세요.');
                        return;
                      }
                      command('work/transition', {
                        'targetStatus': next,
                        if (next == 'REVISIT_REQUIRED')
                          'reason': reason.text.trim(),
                      });
                    },
              child: Text(_ConnectedWorkflowState._workStatus(next)),
            ),
          ExpansionTile(
            title: const Text('작업 취소'),
            children: [
              DropdownButton<String>(
                value: cancellation,
                items: [
                  for (final e in {
                    'OTHER': '기타',
                    'SCHEDULE_CONFLICT': '일정 충돌',
                    'EQUIPMENT_UNAVAILABLE': '장비 문제',
                    'TRAVEL_ISSUE': '이동 문제',
                    'CUSTOMER_UNREACHABLE': '고객 연락 불가',
                  }.entries)
                    DropdownMenuItem(value: e.key, child: Text(e.value)),
                ],
                onChanged: busy
                    ? null
                    : (v) => setState(() => cancellation = v!),
              ),
              if (next != 'REVISIT_REQUIRED') input(reason, '취소 사유'),
              OutlinedButton(
                onPressed: busy
                    ? null
                    : () {
                        if (reason.text.trim().isEmpty) {
                          setState(() => error = '취소 사유를 입력해 주세요.');
                          return;
                        }
                        command('work/cancel', {
                          'reasonCode': cancellation,
                          'reasonDetail': reason.text.trim(),
                        });
                      },
                child: const Text('작업 취소 확정'),
              ),
            ],
          ),
        ],
        if (widget.work['canSubmitReport'] == true) ...[
          for (final phase in photos.keys) ...[
            OutlinedButton(
              onPressed: busy ? null : () => addPhotos(phase),
              child: Text(
                '작업 ${phase == 'BEFORE' ? '전' : '후'} 사진 추가 (${photos[phase]!.length}/5)',
              ),
            ),
            for (final entry in photos[phase]!.entries)
              ListTile(
                title: Text(entry.value),
                trailing: IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: busy
                      ? null
                      : () => setState(() => photos[phase]!.remove(entry.key)),
                ),
              ),
          ],
          input(description, '작업 내용'),
          input(diagnosis, '진단 결과'),
          input(cost, '작업 비용(원)', number: true),
          FilledButton(
            onPressed: busy
                ? null
                : () {
                    final amount = int.tryParse(cost.text.trim());
                    if (description.text.trim().isEmpty ||
                        diagnosis.text.trim().isEmpty ||
                        amount == null ||
                        amount < 0 ||
                        photos.values.any((p) => p.length < 3)) {
                      setState(
                        () =>
                            error = '내용·진단·원 단위 비용과 작업 전후 사진 각 3~5장을 입력해 주세요.',
                      );
                      return;
                    }
                    command('work/submit', {
                      'workDescription': description.text.trim(),
                      'diagnosis': diagnosis.text.trim(),
                      'totalCost': amount,
                      'beforePhotoIds': photos['BEFORE']!.keys.toList(),
                      'afterPhotoIds': photos['AFTER']!.keys.toList(),
                    });
                  },
            child: const Text('보고서 제출 · 작업 완료'),
          ),
        ],
        if (error.isNotEmpty) Text(error),
      ],
    );
  }
}
