part of '../allcheck_ui.dart';

class _ConnectedWorkflow extends StatefulWidget {
  const _ConnectedWorkflow({
    super.key,
    required this.api,
    required this.operation,
    this.threadId = '',
    this.onThread,
  });

  final FrontendBackend api;
  final String operation;
  final String threadId;
  final void Function(String)? onThread;

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

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  Future<Json> _load() {
    final today = DateTime.now().toUtc().add(const Duration(hours: 9));
    String date(DateTime value) => value.toIso8601String().substring(0, 10);
    return widget.api.call(widget.operation, {
      'page': _page,
      'threadId': widget.threadId,
      if (_before != null) 'before': _before,
      if (widget.operation == 'dashboard/statistics') ...{
        'from': date(today.subtract(const Duration(days: 29))),
        'to': date(today),
      },
    });
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
}
