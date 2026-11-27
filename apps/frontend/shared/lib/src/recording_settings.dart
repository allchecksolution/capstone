part of '../allcheck_ui.dart';

extension _RecordingCollectionScreens on _WorkflowShellState {
  Future<void> pollRecordings() async {
    if (!admin || !recordingQueue.supported || recordingPolling || !mounted) {
      return;
    }
    if (WidgetsBinding.instance.lifecycleState != null &&
        WidgetsBinding.instance.lifecycleState != AppLifecycleState.resumed) {
      return;
    }
    recordingPolling = true;
    try {
      final snapshot = await recordingQueue.scan();
      if (!mounted) return;
      refresh(() => recordingCollection = snapshot);
      final backend = widget.backend;
      if (snapshot['enabled'] == true &&
          signedIn &&
          !developmentSession &&
          !busy &&
          backend != null && recordingOwnerKey != null) {
        recordingSynchronizer ??= RecordingSynchronizer(
          queue: recordingQueue,
          journal: LocalRecordingJournal(),
          backend: backend,
          currentOwner: () =>
              mounted &&
                  identical(widget.backend, backend) &&
                  signedIn &&
                  !developmentSession &&
                  recordingCollection['enabled'] == true
              ? recordingOwnerKey
              : null,
        );
        final count = await recordingSynchronizer!.sync(snapshot);
        if (!mounted) return;
        refresh(() => recordingUploadError = '');
        if (count > 0) {
          final current = await recordingQueue.status();
          if (mounted) refresh(() => recordingCollection = current);
          if (signedIn && page == 'receptions') await loadReceptions();
        }
      }
    } on MissingPluginException {
      // Unit-test hosts and older installed APKs do not expose this channel.
    } on ServiceFailure catch (e) {
      if (e.status == 401) {
        failure(e);
      }
      if (mounted && e.status != 499) {
        refresh(() => recordingUploadError = e.message);
      }
    } catch (_) {
      if (mounted) {
        refresh(
          () => recordingUploadError =
              '녹음 수집·전송을 완료하지 못했습니다. 폴더 권한과 서버 연결을 확인해 주세요.',
        );
      }
    } finally {
      recordingPolling = false;
    }
  }

  Widget recordingSettings() {
    final supported = recordingQueue.supported;
    final enabled = recordingCollection['enabled'] == true;
    final items = (recordingCollection['items'] as List? ?? [])
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
    final waiting = items.where((e) => e['uploaded'] != true).length;
    return stack([
      heading('Softphone 녹음 자동 수집'),
      notice(
        'Music → Softphone → recordings 폴더의 읽기 권한을 허용해 주세요. 원본은 삭제하지 않습니다.',
      ),
      if (!supported)
        notice('녹음 자동 수집은 Android 관리자앱에서 지원합니다.')
      else ...[
        button(
          '녹음 폴더 연결',
          () => platformTask(() async {
            final allowed = await _native.invokeMethod<bool>(
              'connectRecordings',
            );
            if (allowed == true) {
              message('녹음 폴더를 연결했습니다. 자동 수집을 켜면 새 녹음을 확인합니다.');
            }
          }),
          secondary: true,
        ),
        if (!enabled)
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('기존 폴더의 녹음도 함께 수집'),
            value: includeExistingRecordings,
            onChanged: busy
                ? null
                : (v) => refresh(() => includeExistingRecordings = v == true),
          ),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('자동 수집 및 서버 전송'),
          value: enabled,
          subtitle: const Text(
            '기록이 끝난 WAV를 수집합니다. 서버에 로그인된 상태에서 전송하며, 인증이 없으면 대기합니다.',
          ),
          onChanged: busy
              ? null
              : (v) => platformTask(() async {
                  final result = await recordingQueue.configure(
                    enabled: v,
                    includeExisting: includeExistingRecordings,
                  );
                  if (mounted) {
                    refresh(() {
                      recordingCollection = result;
                      recordingUploadError = '';
                    });
                  }
                }),
        ),
        small(
          '앱 사용 중에는 약 10초마다 확인합니다. 앱이 백그라운드에 있거나 종료된 동안에는 Android 예약 작업으로 확인하며, 배터리 정책에 따라 지연될 수 있습니다. 전송은 인증된 앱을 열면 재개합니다.',
        ),
        small(
          '수집된 목록은 앱을 다시 열어도 유지됩니다. 전송 전까지 원본 파일을 폴더에 남겨 주세요. 자동 전송은 파일당 64MB 이하의 완성된 RIFF/RIFX WAV를 지원합니다.',
        ),
        if (developmentSession ||
            recordingOwnerKey == null)
          notice('현재는 기기에서 녹음을 수집하고 대기합니다. 관리자 서버 로그인이 연결되면 자동 전송할 수 있습니다.'),
        if (text(recordingCollection, 'error').isNotEmpty)
          notice(text(recordingCollection, 'error'), error: true),
        if (recordingUploadError.isNotEmpty)
          notice(recordingUploadError, error: true),
        kv('수집된 녹음', '${items.length}개 · 전송 대기 $waiting개'),
        button('지금 확인 · 전송 재시도', () => pollRecordings(), secondary: true),
        for (final item in items.take(30))
          box([
            Text(
              text(item, 'name'),
              style: _bodyTextStyle,
            ),
            small(
              '${((item['sizeBytes'] as num? ?? 0) / 1024).toStringAsFixed(1)} KB · ${item['uploaded'] == true ? '전송 완료' : '전송 대기'}',
            ),
          ]),
        if (items.length > 30) small('최근 수집 30개를 표시합니다. 나머지 파일도 순서대로 전송합니다.'),
        button(
          '폴더 연결 해제',
          () => platformTask(() async {
            await recordingQueue.configure(enabled: false);
            if (mounted) refresh(() => recordingCollection['enabled'] = false);
            await _native.invokeMethod<void>('disconnectRecordings');
            message('자동 수집과 폴더 연결을 해제했습니다. 원본 파일은 유지됩니다.');
          }),
          secondary: true,
        ),
      ],
    ]);
  }
}
