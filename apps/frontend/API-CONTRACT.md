# 관리자 앱, 기사앱

## 서버 어댑터

`shared/lib/src/backend.dart`의 `FrontendBackend.call(operation, data)`가 유일한 HTTP 연결 지점입니다. 기본 어댑터는 `API_BASE_URL` 뒤에 아래 operation을 붙여 JSON POST를 보냅니다. 아래는 연결을 위한 프론트 계약이며 서버에 이 API들이 이미 있다는 의미가 아닙니다. 기존 REST 규격을 사용하려면 이 인터페이스를 구현해 `launchAllcheck(backend: ...)`로 주입하세요.

HTTPS를 기본으로 사용합니다. debug 빌드의 localhost/127.0.0.1/10.0.2.2만 HTTP를 허용합니다. Android 평문 통신까지 허용하려면 개발용 네트워크 설정이 별도로 필요합니다. 리다이렉트는 따라가지 않으며 응답 본문의 오류/개인정보는 사용자에게 그대로 노출하지 않습니다.

인증 응답의 `accessToken`은 메모리에 보관하고 Bearer 헤더로 전송합니다. 선택적으로 `refreshToken`을 반환하면 401 시 `auth/refresh`를 한 번 호출하고 원래 요청을 재시도합니다. 토큰은 디스크에 보관하지 않으므로 재시작 시 로그인해야 합니다. 서버는 Refresh Token 회전/폐기 정책을 구현해야 합니다. 클라이언트가 보내는 role/id/상태/후보 목록을 권한 검증의 근거로 신뢰하면 안 됩니다.

업무 변경 명령에는 동일 입력 재시도 시 유지되는 `requestId`가 포함됩니다. 서버는 이 키와 인증 주체를 묶어 중복 실행을 방지해야 합니다. 409/410은 이미 처리/만료 상태이며 서버에서 원자적으로 검증해야 합니다. POST 알림 조회·기기 등록·좌표 갱신 등은 서버에서 조회 또는 upsert 의미로 처리합니다.

## 상태 응답

로그인과 업무 변경, 후보 조회, 알림 열기, `workflow/state`는 `{ "state": {...}, "resourceId": "선택사항" }`를 반환합니다. `state`는 인증 사용자에게 허용된 UI projection이며 클라이언트가 이 상태를 쓰기 요청으로 보내지 않습니다. 아래 기본 필드를 유지합니다.

```json
{
  "schema": 1,
  "account": null,
  "draft": {},
  "jobs": [],
  "drivers": [],
  "consultations": [],
  "reports": {},
  "cancellations": [],
  "threads": {},
  "messages": [],
  "role": "editor",
  "availability": "휴식",
  "notifications": [true, true, true, true],
  "location": "미확인"
}
```

계정은 `{status, profile, reason?}` 형식입니다. 상태는 `활성/승인 대기/반려/탈퇴`로 구분합니다. 조회 전용 관리자의 UI role은 `viewer`입니다. 실제 마스터/부 관리자 정책은 서버 권한에서 적용합니다. 관리자는 전체 조회 대상, 기사는 본인의 요청·작업만 포함해야 합니다. 기사 수락 전에는 고객 연락처·정확한 주소를 projection에서 제외하세요.

배차 요청은 `id,type,title,area,symptom,visit,expires,status,assigned`를 포함합니다. `expires`는 UTC ISO-8601, 대기 상태는 `응답 대기`입니다. 수락 후 주소·연락처를 추가하고 상태를 `이동 중/현장 도착/작업 중/작업 완료/재방문 필요` 등으로 갱신합니다. 서버가 최신 상태와 배정 소유권을 검증해야 합니다.

상담은 `id,status,stt?,analysis?,recordingUrl?,customer,phone,address,type,symptom,visit,reviewed,jobId?`를 사용합니다. `analysis`는 최초 원본이며 검토 입력 fields와 별도로 보존하세요. 원문/분석이 실제 존재할 때만 표시합니다. 보고서·업무의 나머지 필드는 `shared/lib/src/store.dart`, `domain.dart` 및 테스트에서 확인할 수 있습니다.

## 작업 목록

| operation | 요청의 주요 필드 | 응답/처리 |
|---|---|---|
| auth/admin-login | username, password | accessToken, refreshToken?, name, state. 2차 인증 없음 |
| auth/kakao | accessToken(카카오) | 서버 검증 후 accessToken(서비스), refreshToken?, account, state? |
| auth/refresh | refreshToken | accessToken, refreshToken? |
| auth/logout | 없음 | 세션/갱신 토큰 폐기 |
| workflow/state | role | 최신 state. 로그인 후 새로고침/복귀/전경 15초 간격 |
| jobs/create | id, fields, selections | state, resourceId(생성 의뢰) |
| jobs/cancel | id | state |
| receptions/review | id, fields, selections | state. 최초 분석 원본 보존, 수정값 별도 저장 |
| receptions/register | id | state, resourceId(의뢰) |
| dispatch/candidates | id, expanded | state.jobs 해당 항목의 candidates 배열에 서버 선별 후보 |
| dispatch/request | id, driverIds, expanded | state. 서버 조건 재검증 후 후보 기사에게 전송 |
| dispatch/respond | id, accept | state. 최초 유효 수락만 성공 |
| dispatch/cancel | id, reason | state. 취소 접수와 배정 해제 원자 처리 |
| cancellations/resolve | id, redispatch | state, resourceId(재배차 의뢰) |
| work/advance | id, status | state |
| work/revisit | id, reason | state |
| drivers/business-check | businessNumber | verified, verificationId |
| drivers/register | profile, consent | state. 신규 계정/보완 재신청을 서버에서 구분 |
| drivers/decide | id, approve, reason? | state |
| drivers/settings | id(설정 분류), fields, selections | state |
| drivers/availability | availability | state |
| drivers/location | latitude, longitude, accuracy, recordedAt | 저장/upsert 응답 |
| drivers/withdraw | 없음 | state. 진행 업무 검증·관련 토큰 폐기 |
| reports/draft | id, report | state |
| reports/submit | id, report | state. 최초 제출·사진 원본 보존, 중복 제출 방지 |
| reports/edit | id, fields, selections | state. 수정 전후·수정자·시각을 서버에서 기록 |
| chat/send | threadId, text | state. 서버가 발신자·대화 상대 접근권한 결정 |
| chat/retry | threadId, messageId | state |
| documents/get | kind(terms/privacy) | content(표시 가능한 실제 문서) |
| notifications/preferences | index, enabled | state. 서버 푸시 발송에도 적용 |
| notifications/list | role | items: 알림 데이터 배열 |
| notifications/open | notificationId | 최신 state, page, resourceId. 접근권한·마감 확인 |
| notifications/read | notificationId | 읽음 처리 |
| devices/register | token, role, platform | 로그인 사용자에 토큰 upsert |
| devices/unregister | token | 로그인 사용자와 토큰 연결 해제 |

`fields`는 화면 입력값, `selections`는 다중 선택 배열입니다. 입력값을 임의 DB 필드에 자동 바인딩하지 말고 각 operation의 허용 항목만 사용하세요. 신규 의뢰/재배차는 resourceId가 필수입니다.

사진은 현재 `{name,base64}` 배열(before/after)을 report와 함께 보냅니다(항목당 3장, 장당 3MB). 서버에서 실제 파일 형식·용량·권한 검증 후 비공개 저장소에 저장하세요. 조회 응답은 `{name,url}` 형태의 만료되는 HTTPS 서명 URL도 지원합니다. 전송 효율이 필요한 경우 어댑터에서 업로드와 제출을 분리할 수 있습니다. 녹음 재생 URL 역시 사용자 권한을 검증한 짧은 만료 서명 URL을 사용합니다.

## Firebase와 알림

관리자/기사 Android 앱을 각 applicationId(`kr.allcheck.flutter.admin`, `kr.allcheck.flutter.driver`)로 Firebase에 등록하고 각 앱의 `android/app/google-services.json`을 준비합니다. 파일이 있을 때만 Google Services Gradle 플러그인이 적용됩니다. iOS는 각 번들 ID로 등록하고 `GoogleService-Info.plist`를 Runner target에 추가하며, Push Notifications capability/프로비저닝과 APNs 키를 설정합니다. entitlements와 remote-notification background mode, UIScene 알림 delegate 코드는 포함돼 있습니다. Firebase 설정 파일/서비스 계정 키는 저장소에 넣지 않습니다.

`ALLCHECK_PUSH_ENABLED=true`로 네이티브 Firebase를 초기화합니다. 앱의 알림 설정에서 권한을 허용하면 인증 세션에 기기 토큰을 등록하고, 토큰 갱신/앱 복귀에서 재등록합니다. 로그아웃 시 토큰 연결 해제·기기 토큰 삭제를 시도하고 로컬 세션은 항상 종료합니다. 서버도 로그아웃/탈퇴 시 토큰 연결을 폐기해야 네트워크 실패 상황의 알림 노출을 방지할 수 있습니다.

FCM은 **notification+data** 형태로 보내세요. notification에는 개인정보가 없는 짧은 문구만 넣고 data에는 다음 문자열 식별자를 넣습니다.

```json
{"notificationId":"n-123","type":"analysis_completed","resourceId":"reception-123","role":"admin"}
```

type: `analysis_completed`, `analysis_failed`, `dispatch_requested`, `dispatch_updated`, `dispatch_cancelled`, `work_updated`, `chat_received`, `driver_approval`.

분석 완료는 관리자에게, 배차 요청은 관리자 최종 확인·요청 후 서버가 선정한 기사들에게 발송합니다. 프론트는 기사 선정이나 실제 발송을 수행하지 않습니다. OS가 백그라운드/종료 상태의 알림을 표시하고 foreground는 인앱 배너로 처리합니다. data-only 메시지의 종료 상태 배달/OS 강제 종료 후 배달은 보장하지 않습니다.

클릭 이벤트는 로그인까지 보류하고 서버 `notifications/open`으로 최신 데이터를 조회합니다. 응답 page는 관리자의 `reception/detail/driver/chat/cancellation/report`, 기사의 `request/detail/chat/home`만 허용합니다. 배차 요청은 expires와 현재 상태를 재검사합니다. 프론트에서 표시한 TTL과 관계없이 실제 수락 원자성은 서버에서 보장해야 합니다. 알림 목록이 source of truth이며 push 수신 자체는 업무 데이터 갱신으로 취급하지 않습니다.

## 위치·요구사항 범위

위치 버튼으로 권한을 얻고 현재 좌표를 전송합니다. 작업 가능 상태가 대기일 때 ‘대기 중 위치 갱신 켜기’를 선택하면 위치 스트림(거리 50m, Android 요청 간격 30초)을 켭니다. Android foreground service, iOS background location 설정을 포함하며 휴식/로그아웃/탈퇴 시 중단합니다. OS 권한·배터리 정책에 따라 주기는 달라지고 프로세스 종료 뒤 자동 재시작은 지원하지 않습니다. 항상 허용이 필요한 기기는 기기 설정에서 승인해야 합니다.

FR-01 관리자 ID/PW, FR-04 원본/검토, FR-05~12 후보/배차/작업/보고서/취소, FR-13~16 가입/설정/채팅/통계의 UI와 서버 명령 연결을 제공합니다. 관리자 2차 인증은 현재 사용자 결정으로 제외하므로 기존 FR-01/SEC-AUTH-02를 담당자와 동기화해야 합니다. FR-02 녹음 자동 확보/통화 종료 감지의 수집 시스템 PoC, FR-03 실제 분석, FCM 발송·최적 기사 계산·DB 동시성·파일 보존·서버 권한·성능/가용성 검증은 이 프론트 구현으로 완료되지 않습니다.

외부 도구를 통한 Softphone 녹음 자동 수집은 아직 연동하지 않았습니다. 관리자 수동 접수·녹음 폴더 권한·분석 자료 표시를 제공하며 실제 자동 수집 방식과 OS 정책 검증은 별도입니다. 서버 연결 계약과 Firebase 프로젝트 설정을 완료한 뒤 실기기에서 인증/알림/위치/사진을 검증해야 합니다.

설정 근거: [Firebase Flutter 메시지 수신](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages), [Firebase Messaging SDK](https://pub.dev/packages/firebase_messaging), [Geolocator](https://pub.dev/packages/geolocator).
