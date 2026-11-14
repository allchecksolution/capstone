part of '../allcheck_ui.dart';
extension AdminOperations on AppStore {
  void reviewReception(String id, Json values) {
    requireEditor(); _required(values,['customer','phone','address','type','symptom']); final c = find(consultations,id);
    if (c['jobId'] != null) throw StateError('이미 의뢰로 등록된 접수입니다.');
    c.addAll({...values,'reviewed':true,'status':'검토 완료'});
  }
  String registerReception(String id) {
    requireEditor(); final c = find(consultations,id); if (c['reviewed'] != true || c['jobId'] != null) throw StateError('검토 완료 상태를 확인해 주세요.');
    _required(c,['customer','phone','address','type','symptom']); final newId = uid();
    jobs.insert(0, <String,dynamic>{...c,'id':newId,'status':'배차 대기','created':nowIso(),'history':<Json>[],'dispatch':<String,dynamic>{'status':'미요청','attempts':<Json>[]}});
    c.addAll({'jobId':newId,'status':'의뢰 등록'}); return newId;
  }
  List<Json> candidates(String id, {bool expanded = false}) {
    final j = job(id); final busy = jobs.where((j) => j['driverId'] != null && ['배차 확정',...AppStore.workStates].contains(j['status'])).map((j) => j['driverId']).toSet();
    return drivers.where((d) {
      final time = DateTime.tryParse(text(d,'locationAt')); final age = time == null ? -1 : DateTime.now().difference(time).inSeconds;
      return d['status'] == '활성' && d['availability'] == '대기' && !busy.contains(d['id']) && age >= 0 && age <= 900 && strings(d['specialties']).any((s) => text(j,'type').contains(s) || s.contains(text(j,'type'))) && (expanded || strings(d['regions']).any((r) => text(j,'address').contains(r)));
    }).toList();
  }
  void dispatch(String id, List<String> selected, String mode, {bool expanded = false}) {
    requireEditor(); final j = job(id); if (!['배차 대기','배차 요청 중'].contains(j['status']) || j['driverId'] != null) throw StateError('최신 배차 상태와 충돌합니다.');
    if (selected.isEmpty) { j['dispatch'] = <String,dynamic>{'status':'후보 없음','attempts':<Json>[]}; return; }
    final eligible = candidates(id,expanded:expanded).map((d) => d['id']).toSet(); if (selected.any((id) => !eligible.contains(id))) throw StateError('기사의 최신 자격·위치·작업 상태를 다시 확인해 주세요.');
    final attempts = <Json>[]; for (var i=0;i<selected.length;i++) { attempts.add(<String,dynamic>{'driverId':selected[i],'at':nowIso(),'status':mode=='failed'||mode=='partial'&&i==0?'전달 실패':'발행 접수'}); }
    final failed = attempts.where((a) => a['status']=='전달 실패').length;
    final label = failed==attempts.length?'전체 전달 실패':failed>0?'일부 전달 실패':'요청 발행';
    final old = j['dispatch'] as Json?; j['dispatch'] = <String,dynamic>{'status':label,'attempts':<Json>[...records(old?['attempts']),...attempts]}; j['status'] = failed==attempts.length?'배차 대기':'배차 요청 중';
    (j['history'] as List? ?? (j['history']=<Json>[])).add(<String,dynamic>{'at':nowIso(),'text':label});
  }
  void decideDriver(String id, bool approve, String reason) {
    requireEditor(); final d = find(drivers,id); if (d['status']!='승인 대기') throw StateError('이미 처리된 신청입니다.');
    if (approve && (d['businessVerified']!=true || text(d,'name').isEmpty || text(d,'phone').isEmpty || strings(d['specialties']).isEmpty || strings(d['regions']).isEmpty)) throw StateError('미등록 항목과 사업자 검증 결과를 확인해 주세요.');
    if (!approve && reason.trim().isEmpty) throw StateError('반려 사유를 입력해 주세요.'); d.addAll({'status':approve?'활성':'반려','reason':reason.trim(),'decidedAt':nowIso()});
  }
  void editReport(String id, Json values, String actor) {
    requireEditor(); final r = reports[id] as Json?; if (r==null) throw StateError('보고서가 등록되지 않았습니다.');
    _required(values,['content','diagnosis']); final before = Map<String,dynamic>.from(r['current'] as Map); final next = <String,dynamic>{...before,...values};
    if (['travelCost','materialCost','laborCost'].any(next.containsKey)) { var total=0.0; for (final key in ['travelCost','materialCost','laborCost']) { final n = double.tryParse(text(next,key,'0')); if(n==null||n<0) throw StateError('올바른 비용을 입력해 주세요.'); total+=n; } next['cost']=total.toStringAsFixed(0); }
    final cost = double.tryParse(text(next,'cost')); if(cost==null||cost<0) throw StateError('올바른 비용을 입력해 주세요.');
    final changes = <Json>[]; for(final key in next.keys) { if(before[key].toString()!=next[key].toString()) changes.add(<String,dynamic>{'key':key,'before':before[key],'after':next[key]}); }
    if(changes.isEmpty)return; records(r['history']).add(<String,dynamic>{'actor':actor,'at':nowIso(),'changes':changes}); r['current']=next;
  }
  String resolveCancellation(String id, bool redispatch) {
    requireEditor(); final c=find(cancellations,id); if(c['decision']!=null)throw StateError('이미 처리된 요청입니다.'); final j=job(text(c,'jobId'));
    if(j['driverId']!=null||['작업 완료','의뢰 취소'].contains(j['status'])) throw StateError('최신 의뢰 상태와 충돌합니다.');
    c.addAll({'decision':redispatch?'redispatch':'cancel','decidedAt':nowIso()});j['status']=redispatch?'배차 대기':'의뢰 취소';
    (j['history'] as List? ?? (j['history']=<Json>[])).add(<String,dynamic>{'at':nowIso(),'text':redispatch?'재배차 결정':'관리자 의뢰 취소'});return text(j,'id');
  }
  void seedAdmin() {
    requireEditor(); if(drivers.any((d)=>d['id']=='example-active'))return;
    Json driver(String id,String name,String status,{bool valid=true}) => <String,dynamic>{'id':id,'name':name,'status':status,'phone':'010-0000-0000','email':'driver@example.com','company':'예시 설비','owner':name,'businessNumber':'000-00-00000','companyAddress':'서울 강남구','specialties':valid?<String>['막힘','누수','배관','방수']:<String>[],'regions':<String>['강남구','서초구'],'equipment':<String>['고압세척기','누수 탐지기'],'availability':'대기','businessVerified':valid,'locationAt':nowIso()};
    drivers.addAll([driver('example-active','김기사','활성'),driver('example-other','이기사','활성'),driver('example-pending','박기사','승인 대기'),driver('example-incomplete','미등록 기사','승인 대기',valid:false),driver('example-withdrawn','이전 기사','탈퇴')]);
    final c=<String,dynamic>{'id':'example-reception','customer':'예시 고객','phone':'010-0000-0000','address':'서울 강남구 가상 주소','type':'막힘','symptom':'싱크대 물이 내려가지 않습니다.','visit':'방문 시간 협의','status':'분석 완료','stt':'[예시] 싱크대 배수 문제입니다.','analysis':'[예시] 막힘 의심.','created':nowIso()};
    consultations.addAll([c,<String,dynamic>{...c,'id':'example-failed','status':'분석 실패','analysis':null,'stt':null,'customer':'','address':'','symptom':''}]);
    jobs.addAll([<String,dynamic>{...c,'id':'example-completed','status':'작업 완료','driverId':'example-active','history':<Json>[]},<String,dynamic>{...c,'id':'example-cancel-job','status':'배차 대기','history':<Json>[]}]);
    final original=<String,dynamic>{'content':'[예시] 배수관 세척 작업','diagnosis':'이물질','result':'정상 처리','cost':'100000'};
    reports['example-completed']=<String,dynamic>{'original':Map<String,dynamic>.from(original),'current':Map<String,dynamic>.from(original),'before':<Json>[],'after':<Json>[],'history':<Json>[],'submitted':nowIso()};
    cancellations.add(<String,dynamic>{'id':'example-cancellation','jobId':'example-cancel-job','driverId':'example-active','reason':'[예시] 일정 충돌','at':nowIso()});
    (data['threads'] as Json)['example-withdrawn']=<Json>[<String,dynamic>{'id':uid(),'text':'[예시] 탈퇴 전 문의','from':'driver','at':nowIso(),'status':'기기 저장'}];
  }
}
void _required(Json values,List<String> keys) { if(keys.any((k)=>text(values,k).trim().isEmpty))throw StateError('필수 정보를 모두 입력해 주세요.'); }
