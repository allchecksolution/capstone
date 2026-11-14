part of '../allcheck_ui.dart';
class AppStore extends ChangeNotifier {
  AppStore(this.role, {Future<void> Function(String)? save}) : _save = save ?? _noSave, data = empty(role);
  final AppRole role;
  final Future<void> Function(String) _save;
  Json data;
  bool saving = false;
  bool transientSession = false;
  Json get displayNames => data.putIfAbsent('displayNames', () => <String,dynamic>{}) as Json;
  String startupWarning = '';
  static Future<void> _noSave(String _) async {}
  static Json empty(AppRole role) => <String,dynamic>{'schema': 1, 'account': null, 'draft': <String,dynamic>{}, 'jobs': <Json>[], 'drivers': <Json>[], 'consultations': <Json>[], 'reports': <String,dynamic>{}, 'cancellations': <Json>[], 'threads': <String,dynamic>{}, 'messages': <Json>[], 'role': 'editor', 'availability': '휴식', 'notifications': <bool>[true,true,true,true], 'location': '미확인'};
  List<Json> get jobs => records(data['jobs']);
  List<Json> get drivers => records(data['drivers']);
  List<Json> get consultations => records(data['consultations']);
  List<Json> get cancellations => records(data['cancellations']);
  List<Json> get messages => records(data['messages']);
  Json get reports => data['reports'] as Json;
  Json get draft => data['draft'] as Json;
  Json? get account => data['account'] as Json?;
  Json get profile => account?['profile'] as Json? ?? <String,dynamic>{};
  void restore(String raw) {
    final parsed = jsonDecode(raw);
    if (parsed is! Map<String,dynamic> || parsed['schema'] != 1) throw const FormatException('Unsupported state');
    for (final key in ['jobs','drivers','consultations','cancellations','messages']) {
      if (parsed[key] is! List || (parsed[key] as List).any((e) => e is! Map<String,dynamic>)) throw FormatException('Invalid $key');
    }
    if (parsed['reports'] is! Map || parsed['threads'] is! Map || parsed['draft'] is! Map) throw const FormatException('Invalid state');
    data = {...empty(role), ...parsed};
  }
  Future<void> change(void Function() update) async {
    if (saving) throw StateError('처리 중입니다. 잠시 후 다시 시도해 주세요.');
    final previous = jsonEncode(data); saving = true;
    try { update(); if(!transientSession)await _save(jsonEncode(data)); }
    catch (_) { data = jsonDecode(previous) as Json; rethrow; }
    finally { saving = false; notifyListeners(); }
  }
  void requireActive() { if (account?['status'] != '활성') throw StateError('활성 계정에서만 이용할 수 있습니다.'); }
  void requireEditor() { if (data['role'] != 'editor') throw StateError('조회 전용 권한입니다.'); }
  Json find(List<Json> list, String id) => list.firstWhere((e) => e['id'] == id, orElse: () => throw StateError('정보를 찾을 수 없습니다.'));
  static const workStates = ['이동 중','현장 도착','작업 중','재방문 필요'];
  Json job(String id) => find(jobs, id);
  String addSampleRequest() {
    requireActive(); final id = uid();
    jobs.insert(0, <String,dynamic>{'id':id,'type':'막힘','title':'싱크대 물이 내려가지 않아요','area':'서울 강남구 역삼동','address':'서울 강남구 역삼동 (가상 주소)','customer':'예시 고객','phone':'010-0000-0000','symptom':'싱크대 배수가 원활하지 않습니다.','visit':'오늘 · 가능한 빨리','created':nowIso(),'expires':DateTime.now().add(const Duration(minutes:5)).toUtc().toIso8601String(),'status':'응답 대기','assigned':false});
    return id;
  }
  void respond(String id, bool accept, {DateTime? at}) {
    requireActive(); final j = job(id);
    if (j['status'] != '응답 대기') throw StateError('이미 처리되거나 마감된 요청입니다.');
    final expiry = DateTime.tryParse(text(j,'expires'));
    if (expiry == null || !expiry.isAfter(at ?? DateTime.now())) throw StateError('요청 시간이 만료되었습니다.');
    if (accept && jobs.any((x) => x['assigned'] == true && workStates.contains(x['status']))) throw StateError('진행 중인 작업을 먼저 마무리해 주세요.');
    j['status'] = accept ? '이동 중' : '거절'; j['assigned'] = accept;
  }
  void advance(String id, String next) {
    requireActive(); final j = job(id);
    const allowed = {'이동 중':['현장 도착'],'현장 도착':['작업 중'],'작업 중':['작업 완료','재방문 필요'],'재방문 필요':['이동 중']};
    if (j['assigned'] != true || !(allowed[j['status']] ?? <String>[]).contains(next)) throw StateError('허용되지 않는 상태 변경입니다.');
    j['status'] = next; if (next == '작업 완료') j['completed'] = nowIso();
  }
  void cancelWork(String id, String reason) {
    requireActive(); final j = job(id);
    if (j['assigned'] != true || !workStates.contains(j['status'])) throw StateError('취소할 수 없는 작업입니다.');
    if (reason.trim().isEmpty) throw StateError('취소 사유를 입력해 주세요.');
    j.addAll({'status':'배차 취소','assigned':false,'cancelReason':reason.trim(),'cancelAt':nowIso()});
  }
  void submitReport(String id, Json report) {
    requireActive(); final j = job(id);
    if (j['assigned'] != true || j['status'] != '작업 완료') throw StateError('배정된 완료 작업만 제출할 수 있습니다.');
    if ((reports[id] as Json?)?['submitted'] != null) throw StateError('이미 제출된 보고서입니다.');
    if (records(report['before']).isEmpty || records(report['after']).isEmpty || text(report,'content').trim().isEmpty || text(report,'diagnosis').trim().isEmpty || double.tryParse(text(report,'cost')) == null || double.parse(text(report,'cost')) < 0) throw StateError('사진·내용·진단·올바른 비용을 입력해 주세요.');
    reports[id] = {...report,'submitted':nowIso()};
  }
  void withdraw() {
    requireActive(); if (jobs.any((j) => j['assigned'] == true && workStates.contains(j['status']))) throw StateError('진행 중인 작업이 있어 탈퇴할 수 없습니다.');
    account!['status'] = '탈퇴'; data['availability'] = '휴식';
  }
  Json sendMessage(String driverId, String value, {bool fail = false, String? requestId}) {
    if (value.trim().isEmpty) throw StateError('메시지를 입력해 주세요.');
    final List<Json> list;
    if (role == AppRole.driver) { requireActive(); list = messages; }
    else { requireEditor(); if (find(drivers, driverId)['status'] != '활성') throw StateError('읽기 전용 대화입니다.'); final threads = data['threads'] as Json; threads.putIfAbsent(driverId, () => <Json>[]); list = records(threads[driverId]); }
    final id = requestId ?? uid(); final existing = list.where((e) => e['id'] == id); if (existing.isNotEmpty) return existing.first;
    final m = <String,dynamic>{'id':id,'text':value.trim(),'at':nowIso(),'from':role.name,'status':fail?'전송 실패':'기기 저장'}; list.add(m); return m;
  }
  void retryMessage(String driverId, String id) {
    if (role == AppRole.driver) { requireActive(); } else { requireEditor(); if (find(drivers,driverId)['status'] != '활성') throw StateError('읽기 전용 대화입니다.'); }
    final list = role == AppRole.driver ? messages : records((data['threads'] as Json)[driverId]); final m = find(list,id);
    if (m['status'] != '전송 실패') throw StateError('이미 처리된 메시지입니다.'); m['status'] = '기기 저장';
  }
}
