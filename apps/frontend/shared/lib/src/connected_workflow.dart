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

class _ConnectedWorkflowState extends State<_ConnectedWorkflow>
    with WidgetsBindingObserver {
  Timer? _clock;
  WebSocket? _socket;
  bool _connecting = false, _polling = false;
  int _epoch = 0;
  bool get _active =>
      WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
  void _disconnect() {
    _epoch++;
    _socket?.close();
    _socket = null;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _tick();
    } else {
      _disconnect();
    }
  }

  void _tick() {
    if (!mounted || !_active) return;
    if (widget.operation == 'chat/messages') {
      if (_socket == null) {
        unawaited(_connect());
      } else {
        _socket?.add(jsonEncode({'type': 'PING'}));
      }
    }
    unawaited(_poll());
  }

  Future<void> _connect() async {
    final backend = widget.api;
    if (_connecting || backend is! ChatConnection) return;
    final epoch = _epoch;
    _connecting = true;
    try {
      final socket = await (backend as ChatConnection).openChat(
        widget.threadId,
      );
      if (socket == null) return;
      if (!mounted || epoch != _epoch) {
        await socket.close();
        return;
      }
      _socket = socket;
      socket.listen(
        (frame) {
          if (mounted && epoch == _epoch && frame is String) {
            _handleChatFrame(frame);
          }
        },
        onDone: () {
          if (identical(_socket, socket)) _socket = null;
        },
        onError: (Object _) {},
        cancelOnError: false,
      );
    } catch (_) {
      // REST polling remains active during socket/network interruptions.
    } finally {
      _connecting = false;
    }
  }

  void _handleChatFrame(String frame) {
    try {
      final message = jsonDecode(frame);
      if (message is Map &&
          ['MESSAGE_CREATED', 'SUBSCRIBED'].contains(message['type'])) {
        unawaited(_poll());
      }
    } on FormatException {
      // The periodic REST query still catches up.
    }
  }

  Future<void> _poll() async {
    if (_polling || _sending) return;
    final previous = _result;
    final viewingHistory = _before != null;
    _polling = true;
    try {
      final fresh = viewingHistory ? await _syncChat() : await _load();
      if (!viewingHistory &&
          mounted &&
          _active &&
          identical(previous, _result) &&
          !_sending) {
        setState(() => _result = Future.value(fresh));
      }
    } catch (_) {
      /* Keep the last page and retry on the next tick. */
    } finally {
      _polling = false;
    }
  }

  final _message = TextEditingController();
  late Future<Json> _result;
  int _page = 1;
  String? _before;
  bool _sending = false;
  String? _readMarker;
  Json? _latestChat;
  Future<Json>? _chatSync;
  int _chatRoomVersion = 0;
  String? _draftKey, _draftText;
  String _sendError = '';
  final Map<String, String> _offerKeys = {};

  @override
  void initState() {
    super.initState();
    _result = _load();
    WidgetsBinding.instance.addObserver(this);
    _clock = Timer.periodic(const Duration(seconds: 5), (_) => _tick());
    if (_active && widget.operation == 'chat/messages') unawaited(_connect());
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
      _disconnect();
      _readMarker = null;
      _latestChat = null;
      _chatSync = null;
      _chatRoomVersion++;
      _page = 1;
      _sendError = '';
    }
    _before = null;
    _result = _load();
  }

  @override
  void dispose() {
    _clock?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _disconnect();
    _message.dispose();
    super.dispose();
  }

  Future<Json> _syncChat() {
    final running = _chatSync;
    if (running != null) return running;
    late final Future<Json> pending;
    pending = _fetchLatestChat().whenComplete(() {
      if (identical(_chatSync, pending)) _chatSync = null;
    });
    return _chatSync = pending;
  }

  Future<Json> _fetchLatestChat() async {
    final api = widget.api;
    final threadId = widget.threadId;
    final roomVersion = _chatRoomVersion;
    final previous = _latestChat;
    final existing = records(previous?['items']);
    final newest = existing.firstOrNull?['messageId'] as String?;
    if (newest == null) {
      final first = await api.call('chat/messages', {'threadId': threadId});
      if (mounted && roomVersion == _chatRoomVersion) _latestChat = first;
      return first;
    }

    final seen = {for (final message in existing) message['messageId']};
    final added = <Json>[];
    final cursors = {newest};
    var cursor = newest;
    var readOnly = previous!['readOnly'];
    while (mounted && roomVersion == _chatRoomVersion) {
      final page = await api.call('chat/messages', {
        'threadId': threadId,
        'after': cursor,
      });
      readOnly = page['readOnly'];
      for (final message in records(page['items'])) {
        if (seen.add(message['messageId'])) added.add(message);
      }
      final next = page['nextCursor'] as String?;
      if (next == null) {
        final updated = {
          ...previous,
          'items': [...added.reversed, ...existing],
          'readOnly': readOnly,
        };
        if (mounted && roomVersion == _chatRoomVersion) _latestChat = updated;
        return updated;
      }
      if (!cursors.add(next)) {
        throw const ServiceFailure(0, '대화 목록을 다시 불러와 주세요.');
      }
      cursor = next;
    }
    return previous;
  }

  Future<Json> _load() async {
    final today = DateTime.now().toUtc().add(const Duration(hours: 9));
    String date(DateTime value) => value.toIso8601String().substring(0, 10);
    final operation = widget.operation;
    final thread = widget.threadId;
    final latest = _before == null;
    final roomVersion = _chatRoomVersion;
    final result = operation == 'chat/messages' && latest
        ? await _syncChat()
        : await widget.api.call(operation, {
            'page': _page,
            'threadId': thread,
            if (_before != null) 'before': _before,
            if (operation == 'dashboard/statistics') ...{
              'from': date(today.subtract(const Duration(days: 29))),
              'to': date(today),
            },
          });
    if (operation == 'driver/offer' &&
        mounted &&
        widget.threadId == thread &&
        result['viewedAt'] == null) {
      unawaited(
        _acknowledgeOffer(thread, received: result['receivedAt'] != null),
      );
    }
    if (operation == 'chat/messages' &&
        latest &&
        mounted &&
        widget.operation == operation &&
        roomVersion == _chatRoomVersion &&
        _before == null)
      unawaited(_markRead(result));
    return result;
  }

  /// Opening the newest page reads everything shown; the server keeps the
  /// position monotonic, so a repeated or older marker is harmless.
  Future<void> _markRead(Json page) async {
    final newest = records(page['items']).firstOrNull;
    if (page['readOnly'] == true || newest == null) return;
    final roomVersion = _chatRoomVersion;
    final marker = text(newest, 'messageId');
    if (marker == _readMarker) return;
    try {
      await widget.api.call('chat/read', {
        'threadId': widget.threadId,
        'lastReadMessageId': marker,
      });
      if (mounted && roomVersion == _chatRoomVersion) _readMarker = marker;
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
    final roomVersion = _chatRoomVersion;
    try {
      await widget.api.call('chat/send', {
        'threadId': widget.threadId,
        'text': body,
        'requestId': _draftKey,
      });
      try {
        await _chatSync;
      } catch (_) {
        // The send succeeded. Loading again will retry the history request.
      }
      if (!mounted || roomVersion != _chatRoomVersion) return;
      _message.clear();
      _draftKey = _draftText = _before = null;
      setState(() => _result = _load());
    } catch (error) {
      if (mounted && roomVersion == _chatRoomVersion) {
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

  Future<void> _acknowledgeOffer(
    String offerId, {
    required bool received,
  }) async {
    try {
      if (!received)
        await widget.api.call('offer/received', {'offerId': offerId});
      if (mounted && widget.threadId == offerId)
        await widget.api.call('offer/viewed', {'offerId': offerId});
    } catch (_) {
      // The next detail refresh can retry the acknowledgement.
    }
  }

  Future<void> _followUp(Json request, String decision, String label) async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('$label 처리할까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('닫기'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('확인'),
          ),
        ],
      ),
    );
    if (accepted != true || !mounted || _sending) return;
    final body = {
      'expectedVersion': request['version'],
      'target': request['followUpTarget'],
      'decision': decision,
    };
    final key = jsonEncode(body);
    setState(() {
      _sending = true;
      _sendError = '';
    });
    try {
      await widget.api.call('dashboard/follow-up', {
        'threadId': request['requestId'],
        'body': body,
        'requestId': _offerKeys.putIfAbsent(key, uid),
      });
      _offerKeys.remove(key);
      if (mounted) setState(() => _result = _load());
    } catch (error) {
      if (mounted)
        setState(() {
          _sendError = error is ServiceFailure
              ? error.message
              : '후속 조치에 실패했습니다.';
          if (error is ServiceFailure && error.status == 409) _result = _load();
        });
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
      final result = await widget.api.call('driver/respond', {
        'offerId': id,
        'response': response,
        'requestId': _offerKeys.putIfAbsent(key, uid),
      });
      _offerKeys.remove(key);
      if (!mounted) return;
      final workId = result['resourceId'];
      if (widget.operation == 'driver/offer' &&
          response == 'ACCEPT' &&
          workId is String &&
          widget.onOpen != null) {
        widget.onOpen!(workId);
      } else {
        setState(() => _result = _load());
      }
    } catch (error) {
      if (mounted)
        setState(() {
          _sendError = error is ServiceFailure
              ? error.message
              : '배차 응답에 실패했습니다.';
          if (error is ServiceFailure && error.status == 409) _result = _load();
        });
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) => FutureBuilder<Json>(
    key: ValueKey('${widget.operation}/${widget.threadId}'),
    future: _result,
    builder: (context, snapshot) {
      if (snapshot.connectionState != ConnectionState.done &&
          !snapshot.hasData) {
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
      if (widget.operation == 'dashboard/request') return _requestDetail(data);
      if (widget.operation == 'driver/offer')
        return Column(
          children: [
            _row('방문 지역', text(data, 'addressArea')),
            _row('증상', text(data, 'symptoms')),
            _row('방문 일정', _visitWindow(data)),
            _row('응답 기한', _dateTime(data['expiresAt'])),
            if (data['canRespond'] == true) ...[
              FilledButton(
                onPressed: _sending ? null : () => _respond(data, 'ACCEPT'),
                child: const Text('수락'),
              ),
              OutlinedButton(
                onPressed: _sending ? null : () => _respond(data, 'REJECT'),
                child: const Text('거절'),
              ),
            ] else
              _row('배차 상태', text(data, 'offerStatus')),
            if (_sendError.isNotEmpty) Text(_sendError),
          ],
        );
      if (widget.operation == 'driver/statistics')
        return Column(
          children: [
            for (final e in {
              'assignments': '배정',
              'completed': '완료',
              'cancelled': '취소',
            }.entries)
              _row(e.value, text(data, e.key)),
            for (final type in records(data['workTypes']))
              _row(
                _workTypes[type['workType']] ?? text(type, 'workType'),
                text(type, 'count'),
              ),
          ],
        );
      if (widget.operation == 'work/report-detail') {
        final latest = data['latest'] as Json? ?? {};
        return Column(
          children: [
            ..._reportContent(latest),
            for (final phase in ['beforePhotoIds', 'afterPhotoIds'])
              for (final photo in strings(data[phase]).indexed)
                OutlinedButton(
                  onPressed: () => _photo(data, photo.$2),
                  child: Text(
                    '작업 ${phase == 'beforePhotoIds' ? '전' : '후'} 사진 ${photo.$1 + 1}',
                  ),
                ),
            ExpansionTile(
              title: const Text('원본 및 수정 이력'),
              children: [
                ..._reportContent(data['original'] as Json? ?? {}),
                for (final rev in records(data['revisions'])) ...[
                  _row('수정 사유', text(rev, 'reason')),
                  ..._reportContent(rev['after'] as Json),
                ],
              ],
            ),
            if (widget.api is AdminRestBackend)
              OutlinedButton(
                onPressed: _sending ? null : () => _revise(data, latest),
                child: const Text('보고서 수정'),
              ),
            if (_sendError.isNotEmpty) Text(_sendError),
          ],
        );
      }
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
            if (rooms) const Text('새 대화는 기사 관리에서 기사를 선택해 시작할 수 있습니다.'),
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
                        Text(_visitWindow(row)),
                        TextButton(
                          onPressed: () =>
                              widget.onOpen?.call(text(row, 'offerId')),
                          child: const Text('제안 상세'),
                        ),
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
              else if (widget.operation == 'work/reports')
                ListTile(
                  title: Text(text(row, 'workId')),
                  subtitle: Text(_dateTime(row['submittedAt'])),
                  onTap: () => widget.onOpen?.call(text(row, 'reportId')),
                )
              else if (widget.operation == 'driver/works' ||
                  widget.operation == 'driver/history')
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
                  subtitle: Text(
                    row['cancellation'] is Json
                        ? text(row['cancellation'] as Json, 'reasonDetail')
                        : text(row, 'symptoms'),
                  ),
                  onTap: widget.onOpen == null
                      ? null
                      : () => widget.onOpen!(text(row, 'requestId')),
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
    return from.length == 16 &&
            to.length == 16 &&
            from.substring(0, 10) == to.substring(0, 10)
        ? '$from ~ ${to.substring(11)}'
        : '$from ~ $to';
  }

  Widget _row(String label, String value) => ListTile(
    contentPadding: EdgeInsets.zero,
    title: Text(label),
    subtitle: SelectableText(value.isEmpty ? '-' : value),
  );

  List<Widget> _reportContent(Json data) => [
    _row('작업 내용', text(data, 'workDescription')),
    _row('진단 결과', text(data, 'diagnosis')),
    _row('작업 비용(원)', text(data, 'totalCost')),
  ];

  Future<void> _photo(Json report, String id) async {
    try {
      final grant = await widget.api.call('work/photo-access', {
        'workId': report['workId'],
        'photoId': id,
      });
      final uri = Uri.parse(grant['downloadUrl'] as String);
      if (uri.scheme != 'https' || uri.host.isEmpty || grant['method'] != 'GET')
        throw const FormatException();
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          content: Image.network(
            uri.toString(),
            errorBuilder: (_, __, ___) => const Text('사진을 다시 열어 주세요.'),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('닫기'),
            ),
          ],
        ),
      );
    } catch (_) {
      if (mounted) setState(() => _sendError = '사진을 열지 못했습니다.');
    }
  }

  Future<void> _revise(Json report, Json latest) async {
    final changes = <String, dynamic>{};
    String reason = '';
    final body = await showDialog<Json>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, refresh) => AlertDialog(
          title: const Text('보고서 수정'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final e in {
                  'workDescription': '작업 내용',
                  'diagnosis': '진단 결과',
                  'totalCost': '작업 비용(원)',
                }.entries)
                  TextFormField(
                    initialValue: text(latest, e.key),
                    decoration: InputDecoration(labelText: e.value),
                    onChanged: (v) => refresh(() {
                      if (v.trim() == text(latest, e.key)) {
                        changes.remove(e.key);
                      } else {
                        changes[e.key] = e.key == 'totalCost'
                            ? int.tryParse(v.trim())
                            : v.trim();
                      }
                    }),
                  ),
                TextField(
                  decoration: const InputDecoration(labelText: '수정 사유'),
                  onChanged: (v) => refresh(() => reason = v.trim()),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('닫기'),
            ),
            FilledButton(
              onPressed:
                  reason.isEmpty ||
                      changes.isEmpty ||
                      changes.values.any(
                        (v) => v == null || v == '' || v is int && v < 0,
                      )
                  ? null
                  : () => Navigator.pop(context, {
                      'expectedVersion': report['version'],
                      'changes': changes,
                      'reason': reason,
                    }),
              child: const Text('저장'),
            ),
          ],
        ),
      ),
    );
    if (body == null || !mounted) return;
    setState(() {
      _sending = true;
      _sendError = '';
    });
    try {
      await widget.api.call('work/revise', {
        'reportId': report['reportId'],
        'body': body,
        'requestId': _offerKeys.putIfAbsent(jsonEncode(body), uid),
      });
      if (mounted) setState(() => _result = _load());
    } catch (e) {
      if (mounted)
        setState(
          () =>
              _sendError = e is ServiceFailure ? e.message : '보고서를 수정하지 못했습니다.',
        );
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  Widget _requestDetail(Json request) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _row('의뢰 상태', text(request, 'requestStatus')),
      _row('현장 주소', text(request, 'address')),
      _row('증상', text(request, 'symptoms')),
      _row('방문 일정', _visitWindow(request)),
      _row('고객 연락처', text(request, 'contactPhone')),
      _row('특이사항', text(request, 'notes')),
      if (request['followUpTarget'] is Json) ...[
        _row('후속 조치 사유', text(request, 'followUpReason')),
        FilledButton(
          onPressed: _sending
              ? null
              : () => _followUp(request, 'REDISPATCH', '재배차'),
          child: const Text('재배차'),
        ),
        OutlinedButton(
          onPressed: _sending
              ? null
              : () => _followUp(request, 'CANCEL_REQUEST', '의뢰 취소'),
          child: const Text('의뢰 취소'),
        ),
      ],
      if (_sendError.isNotEmpty) Text(_sendError),
    ],
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
          subtitle: SelectableText(phone ?? '완료·취소된 작업은 연락처를 표시하지 않습니다.'),
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
        if (work['reportId'] is String)
          OutlinedButton(
            onPressed: () => widget.onOpen?.call(text(work, 'reportId')),
            child: const Text('작업 보고서'),
          ),
        if (work['canTransition'] == true || work['canSubmitReport'] == true)
          _WorkActions(
            key: ValueKey(widget.threadId),
            work: work,
            api: widget.api,
            onChanged: () {
              setState(() => _result = _load());
            },
          ),
      ],
    );
  }
}
