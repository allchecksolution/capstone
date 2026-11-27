part of '../allcheck_ui.dart';

class _ConnectedWorkflow extends StatefulWidget {
  const _ConnectedWorkflow({
    super.key,
    required this.api,
    required this.operation,
    this.threadId = '',
    this.onThread,
    this.onOpen,
    this.reload = 0,
  });

  final FrontendBackend api;
  final String operation;
  final String threadId;
  final void Function(String)? onThread;

  /// Opens a row's detail page; currently a driver's work by `workId`.
  final void Function(String)? onOpen;

  /// Incremented by the shell's explicit refresh to reload the current query.
  final int reload;

  @override
  State<_ConnectedWorkflow> createState() => _ConnectedWorkflowState();
}

class _ConnectedWorkflowState extends State<_ConnectedWorkflow> {
  final _message = TextEditingController();
  late Future<Json> _result;
  int _page = 1;
  String? _before;
  bool _sending = false;
  String? _draftKey, _draftText;
  String _sendError = '';
  final Map<String, String> _offerKeys = {};

  @override
  void initState() {
    super.initState();
    _result = _load();
  }

  /// The shell reuses this State when only the route's query changes, e.g.
  /// statistics → work list, so reload whenever the query or refresh changes.
  @override
  void didUpdateWidget(covariant _ConnectedWorkflow oldWidget) {
    super.didUpdateWidget(oldWidget);
    final queryChanged =
        oldWidget.operation != widget.operation ||
        oldWidget.threadId != widget.threadId;
    if (!queryChanged && oldWidget.reload == widget.reload) return;
    if (queryChanged) {
      _page = 1;
      _sendError = '';
    }
    _before = null;
    _result = _load();
  }

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  Future<Json> _load() async {
    final today = DateTime.now().toUtc().add(const Duration(hours: 9));
    String date(DateTime value) => value.toIso8601String().substring(0, 10);
    final operation = widget.operation;
    final latest = _before == null;
    final result = await widget.api.call(operation, {
      'page': _page,
      'threadId': widget.threadId,
      if (_before != null) 'before': _before,
      if (operation == 'dashboard/statistics') ...{
        'from': date(today.subtract(const Duration(days: 29))),
        'to': date(today),
      },
    });
    if (operation == 'chat/messages' && latest) unawaited(_markRead(result));
    return result;
  }

  /// Opening the newest page reads everything shown; the server keeps the
  /// position monotonic, so a repeated or older marker is harmless.
  Future<void> _markRead(Json page) async {
    final newest = records(page['items']).firstOrNull;
    if (page['readOnly'] == true || newest == null) return;
    try {
      await widget.api.call('chat/read', {
        'threadId': widget.threadId,
        'lastReadMessageId': text(newest, 'messageId'),
      });
    } catch (_) {
      // Unread counts recover on the next successful open.
    }
  }

  Future<void> _send() async {
    if (_sending) return;
    final body = _message.text.trim();
    if (body.isEmpty) return;
    if (_draftText != body) {
      _draftText = body;
      _draftKey = uid();
    }
    setState(() {
      _sending = true;
      _sendError = '';
    });
    try {
      await widget.api.call('chat/send', {
        'threadId': widget.threadId,
        'text': body,
        'requestId': _draftKey,
      });
      if (!mounted) return;
      _message.clear();
      _draftKey = _draftText = _before = null;
      setState(() => _result = _load());
    } catch (error) {
      if (mounted) {
        setState(
          () => _sendError = error is ServiceFailure
              ? error.message
              : '전송에 실패했습니다.',
        );
      }
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  Future<void> _respond(Json offer, String response) async {
    if (_sending) return;
    final id = text(offer, 'offerId');
    final key = '$id/$response';
    setState(() {
      _sending = true;
      _sendError = '';
    });
    try {
      await widget.api.call('driver/respond', {
        'offerId': id,
        'response': response,
        'requestId': _offerKeys.putIfAbsent(key, uid),
      });
      _offerKeys.remove(key);
      if (mounted) setState(() => _result = _load());
    } catch (error) {
      if (mounted)
        setState(
          () => _sendError = error is ServiceFailure
              ? error.message
              : '배차 응답에 실패했습니다.',
        );
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<Json>(
    future: _result,
    builder: (context, snapshot) {
      if (snapshot.connectionState != ConnectionState.done) {
        return const Center(child: CircularProgressIndicator());
      }
      if (snapshot.hasError) {
        final error = snapshot.error;
        return Column(
          children: [
            Text(error is ServiceFailure ? error.message : '조회에 실패했습니다.'),
            OutlinedButton(
              onPressed: () => setState(() => _result = _load()),
              child: const Text('다시 조회'),
            ),
          ],
        );
      }
      final data = snapshot.data ?? {};
      if (widget.operation == 'driver/work') return _workDetail(data);
      final items = records(data['items']);
      final messages = widget.operation == 'chat/messages';
      final rooms = widget.operation == 'chat/rooms';
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          OutlinedButton(
            onPressed: _sending
                ? null
                : () => setState(() {
                    _page = 1;
                    _before = null;
                    _result = _load();
                  }),
            child: const Text('새로고침'),
          ),
          if (widget.operation == 'dashboard/statistics') ...[
            const Text('최근 30일 작업 통계 · 한국시간'),
            if (data['metrics'] case final Json metrics)
              for (final entry in const {
                'totalRequests': '전체 의뢰',
                'openRequests': '진행 중',
                'completedRequests': '완료',
                'cancelledRequests': '취소',
                'totalCostKrw': '총 작업비(원)',
              }.entries)
                ListTile(
                  title: Text(entry.value),
                  trailing: Text(text(metrics, entry.key)),
                )
            else
              const Text('조회 기간에 의뢰가 없습니다.'),
          ] else ...[
            if (rooms)
              const Text('새 대화는 기사 관리에서 기사를 선택해 시작할 수 있습니다.'),
            if (items.isEmpty) const Text('조회 결과가 없습니다.'),
            for (final row in items)
              if (rooms)
                ListTile(
                  title: Text(text(row['driver'] as Json, 'name')),
                  subtitle: Text(text(row['lastMessage'] as Json, 'body')),
                  trailing: Text(text(row, 'unreadCount')),
                  onTap: () =>
                      widget.onThread!(text(row['driver'] as Json, 'driverId')),
                )
              else if (messages)
                ListTile(
                  title: Text(text(row, 'body')),
                  subtitle: Text(
                    '${row['sender'] == 'ADMIN' ? '관리자' : '기사'} · ${text(row, 'sentAt')}',
                  ),
                )
              else if (widget.operation == 'driver/offers')
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(text(row, 'addressArea')),
                        Text(text(row, 'symptoms')),
                        Text(text(row, 'desiredVisitFrom')),
                        if (row['canRespond'] == true)
                          Wrap(
                            spacing: 8,
                            children: [
                              FilledButton(
                                onPressed: _sending
                                    ? null
                                    : () => _respond(row, 'ACCEPT'),
                                child: const Text('수락'),
                              ),
                              OutlinedButton(
                                onPressed: _sending
                                    ? null
                                    : () => _respond(row, 'REJECT'),
                                child: const Text('거절'),
                              ),
                            ],
                          )
                        else
                          Text(text(row, 'offerStatus')),
                      ],
                    ),
                  ),
                )
              else if (widget.operation == 'driver/works')
                ListTile(
                  title: Text(text(row, 'address')),
                  subtitle: Text(
                    '${text(row, 'symptoms')}\n방문 ${_visitWindow(row)}',
                  ),
                  isThreeLine: true,
                  trailing: Text(_workStatus(text(row, 'workStatus'))),
                  onTap: widget.onOpen == null
                      ? null
                      : () => widget.onOpen!(text(row, 'workId')),
                )
              else
                ListTile(
                  title: Text(text(row, 'address')),
                  subtitle: Text(text(row, 'symptoms')),
                  trailing: Text(
                    text(row, 'requestStatus', text(row, 'workStatus')),
                  ),
                ),
            if (_page > 1 || data['hasNext'] == true)
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  TextButton(
                    onPressed: _page > 1
                        ? () => setState(() {
                            _page--;
                            _result = _load();
                          })
                        : null,
                    child: const Text('이전'),
                  ),
                  Text('$_page'),
                  TextButton(
                    onPressed: data['hasNext'] == true
                        ? () => setState(() {
                            _page++;
                            _result = _load();
                          })
                        : null,
                    child: const Text('다음'),
                  ),
                ],
              ),
          ],
          if (messages)
            if (data['nextCursor'] case final String cursor)
              TextButton(
                onPressed: _sending
                    ? null
                    : () => setState(() {
                        _before = cursor;
                        _result = _load();
                      }),
                child: const Text('이전 대화'),
              ),
          if (messages && data['readOnly'] == true) const Text('읽기 전용 대화입니다.'),
          if (messages && data['readOnly'] != true) ...[
            TextField(
              controller: _message,
              enabled: !_sending,
              maxLength: 2000,
              decoration: const InputDecoration(labelText: '메시지'),
            ),
            FilledButton(
              onPressed: _sending ? null : _send,
              child: const Text('전송'),
            ),
          ],
          if (_sendError.isNotEmpty) Text(_sendError),
        ],
      );
    },
  );

  static const _workStatuses = {
    'NOT_STARTED': '출발 전',
    'EN_ROUTE': '이동 중',
    'ARRIVED': '현장 도착',
    'IN_PROGRESS': '작업 중',
    'REVISIT_REQUIRED': '재방문 필요',
    'WORK_DONE': '작업 완료',
    'CANCELLED': '작업 취소',
  };
  static const _workTypes = {
    'BLOCKAGE': '막힘',
    'EXTERIOR_LEAK': '외벽 누수',
    'INTERIOR_LEAK': '내벽 누수',
    'BOILER': '보일러',
    'WATERPROOFING': '방수',
  };

  static String _workStatus(String value) => _workStatuses[value] ?? value;

  static String _dateTime(Object? value) {
    final parsed = DateTime.tryParse('${value ?? ''}')?.toLocal();
    if (parsed == null) return '-';
    String two(int n) => n.toString().padLeft(2, '0');
    return '${parsed.year}-${two(parsed.month)}-${two(parsed.day)} '
        '${two(parsed.hour)}:${two(parsed.minute)}';
  }

  static String _visitWindow(Json row) {
    final from = _dateTime(row['desiredVisitFrom']);
    final to = _dateTime(row['desiredVisitTo']);
    // Same-day windows only repeat the time on the right-hand side.
    return from.length == 16 && to.length == 16 && from.substring(0, 10) == to.substring(0, 10)
        ? '$from ~ ${to.substring(11)}'
        : '$from ~ $to';
  }

  Widget _row(String label, String value) => ListTile(
    contentPadding: EdgeInsets.zero,
    title: Text(label),
    subtitle: SelectableText(value.isEmpty ? '-' : value),
  );

  Widget _workDetail(Json work) {
    final phone = work['contactPhone'] as String?;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        OutlinedButton(
          onPressed: () => setState(() => _result = _load()),
          child: const Text('새로고침'),
        ),
        _row('진행 상태', _workStatus(text(work, 'workStatus'))),
        _row(
          '작업 유형',
          _workTypes[text(work, 'workType')] ?? text(work, 'workType'),
        ),
        _row('현장 주소', text(work, 'address')),
        _row('방문 일정', _visitWindow(work)),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('고객 연락처'),
          subtitle: SelectableText(
            phone ?? '완료·취소된 작업은 연락처를 표시하지 않습니다.',
          ),
          trailing: phone == null
              ? null
              : IconButton(
                  tooltip: '연락처 복사',
                  icon: const Icon(Icons.copy),
                  onPressed: () async {
                    await Clipboard.setData(ClipboardData(text: phone));
                    if (mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('연락처를 복사했습니다.')),
                      );
                    }
                  },
                ),
        ),
        _row('증상', text(work, 'symptoms')),
        _row('특이사항', text(work, 'notes')),
        _row('배정 시각', _dateTime(work['assignedAt'])),
      ],
    );
  }
}
