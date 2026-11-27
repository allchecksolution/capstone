part of '../allcheck_ui.dart';

extension _BusinessOcrScreens on _WorkflowShellState {
  Widget businessOcrActions() => box([
    heading('사업자등록증으로 자동 입력'),
    small('촬영하거나 사진을 선택한 뒤 인식한 항목을 확인해 주세요. 인식 결과는 수정할 수 있습니다.'),
    if (businessScanner.supported) ...[
      button(
        '사업자등록증 촬영',
        () => scanBusinessDocument(camera: true),
        secondary: true,
      ),
      button(
        '사진에서 사업자등록증 선택',
        () => scanBusinessDocument(camera: false),
        secondary: true,
      ),
    ] else
      small('자동 인식은 Android 앱에서 지원합니다. 아래 항목을 직접 입력해 주세요.'),
  ]);

  Future<void> scanBusinessDocument({required bool camera}) =>
      platformTask(() async {
        await s.change(
          () => s.draft.addAll(
            values([
              'businessNumber',
              'company',
              'owner',
              'companyPhone',
              'businessType',
              'businessItem',
              'companyAddress',
            ]),
          ),
        );
        final found = await businessScanner.scan(camera: camera);
        if (found == null || !mounted) return;
        if (found.values.isEmpty) {
          message('입력할 항목을 찾지 못했습니다. 다시 촬영하거나 직접 입력해 주세요.');
          return;
        }
        final accepted = await showDialog<Map<String, String>>(
          context: context,
          builder: (_) => _BusinessReviewDialog(
            found: found.values,
            existing: values(found.values.keys),
          ),
        );
        if (accepted == null || accepted.isEmpty || !mounted) return;
        await s.change(() {
          for (final e in accepted.entries) {
            controller(e.key).text = e.value;
            s.draft[e.key] = e.value;
          }
          s.draft.remove('businessChecked');
          s.draft.remove('checkedNumber');
          s.draft.remove('checkedIdentity');
          s.draft.remove('businessVerificationId');
        });
        message('선택한 항목을 반영했습니다. 내용 확인 후 사업자 정보 확인을 진행해 주세요.');
      });
}

class _BusinessReviewDialog extends StatefulWidget {
  const _BusinessReviewDialog({required this.found, required this.existing});
  final Map<String, String> found;
  final Map<String, dynamic> existing;
  @override
  State<_BusinessReviewDialog> createState() => _BusinessReviewDialogState();
}

class _BusinessReviewDialogState extends State<_BusinessReviewDialog> {
  late final inputs = {
    for (final e in widget.found.entries)
      e.key: TextEditingController(text: e.value),
  };
  late final selected = {
    for (final key in inputs.keys)
      if ((widget.existing[key] ?? '').toString().isEmpty) key,
  };
  @override
  void dispose() {
    for (final input in inputs.values) {
      input.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('인식한 사업자 정보 확인'),
    content: SizedBox(
      width: 500,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('기존 입력값이 있는 항목은 선택하지 않았습니다. 덮어쓰려면 해당 항목을 선택해 주세요.'),
            for (final key in inputs.keys)
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Checkbox(
                    value: selected.contains(key),
                    onChanged: (yes) => setState(
                      () => yes == true
                          ? selected.add(key)
                          : selected.remove(key),
                    ),
                  ),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: TextField(
                        controller: inputs[key],
                        decoration: InputDecoration(
                          labelText: BusinessDocumentFields.labels[key],
                        ),
                        maxLines: key == 'companyAddress' ? 3 : 1,
                      ),
                    ),
                  ),
                ],
              ),
            const Text('자동 입력은 사업자 유효성 확인이 아닙니다. 반영 후 사업자 정보 확인을 진행해 주세요.'),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('취소'),
      ),
      FilledButton(
        onPressed: selected.isEmpty
            ? null
            : () => Navigator.pop(context, {
                for (final key in selected)
                  if (inputs[key]!.text.trim().isNotEmpty)
                    key: inputs[key]!.text.trim(),
              }),
        child: const Text('선택 항목 반영'),
      ),
    ],
  );
}
