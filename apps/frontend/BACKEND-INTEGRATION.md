# 백엔드 API 연동 및 검수 안내

## ACK-41: EC2 접속 주소 통일

관리자·기사 앱의 공통 `API_BASE_URL`은 `https://api.allcheck.cloud`입니다. 두 앱은 `.env`를 `--dart-define-from-file=../.env`로 읽으며, APK 빌드 스크립트도 같은 파일을 전달합니다. API 주소는 빌드 시 포함되므로 변경 후 재빌드·설치가 필요합니다.

관리자·기사·상담 접수 어댑터는 같은 주소 해석기를 사용합니다. origin 또는 기존 `/api/v1` 포함 주소를 받아 실제 요청에 `/api/v1`을 정확히 한 번 적용합니다. 알 수 없는 경로, URL 내 자격증명·쿼리·fragment, 원격 HTTP는 거부합니다. 실서버 검수용 설정에서는 개발용 입장·임시 승인 건너뛰기를 끕니다.

이번 단계는 프론트 접속 설정과 요청 URL 구성만 변경합니다. 기본 관리자 어댑터는 `AdminRestBackend`, 기사 어댑터는 `DriverOnboardingBackend`이며, 상담 접수 어댑터와 관리자 인증의 결합·업무/채팅/푸시 API 추가 연동은 후속 작업입니다. 백엔드·DB·AI·인프라는 변경하지 않습니다.

검증: 공개 HTTPS readiness는 `200 / {"status":"UP"}`, 인증 토큰 없는 `GET /api/v1/auth/accounts/me`는 `401`을 반환했습니다. 공통 패키지 테스트 77개가 통과했으며, 그중 신규 13개는 관리자·기사·상담 접수 요청의 주소 정규화와 잘못된 주소 거부를 검증합니다. 관리자·기사 Android debug APK 빌드가 모두 성공했고 각 APK의 컴파일된 커널에 EC2 API 주소가 포함됐음을 확인했습니다. 산출물은 `apps/frontend/allcheck-admin-flutter-debug.apk`, `apps/frontend/allcheck-driver-flutter-debug.apk`입니다. 실제 계정 로그인과 기기에서의 업무 왕복 검증은 후속 단계입니다.

아래 내용은 ACK-22 시점의 연동·검증 기록입니다. 현재 로그인과 기사 가입 연동 여부는 위 설명 및 실제 어댑터 구현을 기준으로 확인합니다.

## 구현 여부 요약 (ACK-22 기록)

작성 기준은 2026년 11월 20일 ACK-22의 API 연동 커밋 `f7db117`이다. **프론트의 API 호출·화면 연결 코드는 구현했지만, 관리자 인증 의존성 때문에 실서버 전체 동작은 아직 검증하지 못했다.** 아래의 ‘구현’은 실서버 운영 완료를 뜻하지 않는다.

| 구분 | 기능 | 현재 상태 | 남은 작업 또는 제한 |
|---|---|---|---|
| 관리자 | 접수 목록·페이지 이동 | REST 호출 및 화면 구현 | 관리자 인증 연결 후 실서버 조회 검수 |
| 관리자 | 빈 수동 접수 생성 | 요청 및 상세 이동 구현 | 실서버 생성·소유자 확인 검수 |
| 관리자 | 접수 상세·STT 원문·AI 결과 조회 | 응답 매핑 및 표시 구현 | 실제 상담 데이터로 검수 |
| 관리자 | 검토 입력·최종 확정 | DTO 변환·버전·멱등성·화면 구현 | 실서버 확정 및 동시 수정 충돌 검수 |
| 관리자 | 검토 확정 취소 | API 호출 및 화면 구현 | 실서버 상태 전환 검수 |
| 관리자 | 녹음 파일 수동 선택·업로드 | 파일 선택→예약→S3 PUT→완료 통보 구현 | 실제 S3 전송·백엔드 검증 확인 |
| 관리자 | 미완료 녹음 업로드 재시도 | URL 재발급·같은 파일 검증·완료 통보 재시도 구현 | 실제 통신 단절 상황 검수 |
| 관리자 | 녹음 원본 재생 | 접근 URL 발급 및 재생 대화상자 구현 | 실제 서명 URL 재생·만료 검수 |
| 관리자 | 분석 시도별 상태 조회 | API 호출 어댑터만 구현 | 별도 분석 시도·이력 화면 없음 |
| 관리자 | 현재 분석 상태·결과 확인 | 상세 재조회 및 화면 상태 표시 구현 | 실제 STT·AI 서버 전체 처리 검수 |
| 관리자 | 관리자 ID/PW 로그인 | 화면은 기존 구현, 실제 API 미연동 | main의 로그인 API·JWT 요청 인증·관리자 식별 구현 반영 필요 |
| 관리자 | 의뢰 등록·자동 배차 | 기존 화면·로컬 개발 흐름만 있음 | 실제 API가 main에 반영된 뒤 연결 |
| 관리자 | 기사 승인·관리·보고서·채팅·푸시 | 기존 화면·초안 연결 구조 유지 | 실제 API 연동 및 실서버 검수 필요 |
| 기사 | 카카오 SDK 로그인·닉네임·로그아웃 | 기존 구현 유지, 회귀 테스트 통과 | 이번 작업에서 실제 계정 재인증 검수는 하지 않음 |
| 기사 | SDK 로그인 후 승인 대기 화면 | 기존 기기 내 화면 유지 | 서버 가입 신청·실제 승인 상태 조회와 별개 |
| 기사 | 카카오 토큰의 서버 검증·서비스 로그인 | 미연동 | 실제 인증 API가 main에 반영된 뒤 연결 |
| 기사 | 가입·프로필·사업자 조회·정산 정보 | 기존 화면·로컬 개발 흐름 유지 | 실제 기사 API 연결 필요. 필수·선택 변경은 기업체 결정 대기 |
| 기사 | 배차 수락·거절·작업 진행·보고서·채팅 | 기존 화면·로컬 개발 흐름 유지 | 실제 API가 main에 반영된 뒤 연결 |
| 공통 | Softphone 폴더 읽기 권한 | 기존 Android 구현 유지 | 자동 파일 탐색·감시·자동 업로드는 미구현 |
| 공통 | 서버 오류·버전 충돌·세션 종료 처리 | 접수 연동 코드 및 테스트 구현 | 실서버 인증 만료·권한·충돌 시나리오 검수 |

기사앱은 이번 main 반영 범위에서 새로 연결할 기사 API가 없어 **기존 SDK 로그인 유지와 회귀 검증**을 수행했다. 두 앱의 화면이 존재한다는 이유만으로 서버 기능이 연결되었다고 판단하면 안 된다.

현재 에뮬레이터에 설치한 debug APK는 서버 주소·서비스 세션을 주입하지 않은 빌드다. ‘개발용으로 입장’은 기존 로컬 화면 검수용이며, 새 REST 연동의 실서버 검증이나 실제 관리자 인증 성공을 뜻하지 않는다.

## 기준과 적용 범위

main `4bf2148`을 ACK-22 브랜치에 반영했다. 백엔드 상담 접수 구현은 `cd7b870`에서 도입되었다. OpenAPI 초안 대신 다음 실제 구현을 기준으로 연결했다.

- `apps/backend/src/main/kotlin/com/allcheck/reception/presentation/controller/ReceptionController.kt`
- `apps/backend/src/main/kotlin/com/allcheck/reception/presentation/controller/ReceptionRecordingController.kt`
- 두 Controller가 구현하는 `presentation/api/ReceptionApi.kt`, `ReceptionRecordingApi.kt`
- `presentation/dto/request`, `presentation/dto/response`의 요청·응답 클래스
- `global/security/SecurityConfig.kt`의 접근 조건

작업 브랜치는 `feat/ACK-22-admin-app-demo`다. main 직접 푸시와 PR 생성은 하지 않으며, ACK-22 푸시 후 사용자 구동 검수를 거쳐 PR을 진행한다.

## 화면과 실제 API 연결

`FrontendBackend.call`의 operation을 새 `SpringFrontendBackend`에서 실제 REST 요청으로 변환한다. 개별 자원 응답을 접수 화면 데이터로 매핑한다. 존재하지 않는 전체 상태 API를 호출하거나 프론트 상태 전체를 서버에 보내지 않는다.

| 화면 또는 동작 | 프론트 operation | 실제 요청 |
|---|---|---|
| 접수 목록·페이지 이동 | `receptions/list` | `GET /api/v1/receptions?page=1&size=20` |
| 빈 수동 접수 생성 | `receptions/create` | `POST /api/v1/receptions` |
| 접수 상세·원문·분석 결과 | `receptions/detail` | `GET /api/v1/receptions/{id}` |
| 검토 완료·최종값 저장 | `receptions/review` | `POST /api/v1/receptions/{id}/confirmation` |
| 검토 확정 취소 | `receptions/reopen` | `POST /api/v1/receptions/{id}/reopen` |
| 분석 시도 상태 조회 | `receptions/analysis` | `GET /api/v1/receptions/{id}/analyses/{analysisRequestId}` |
| 녹음 업로드 예약 | `receptions/recording-upload` 내부 | `POST /api/v1/receptions/{id}/recordings/upload-requests` |
| 미완료 녹음 URL 재발급 | 같은 operation 내부 | `POST /api/v1/receptions/{id}/recordings/{fileId}/upload-requests` |
| 녹음 원본 전송 | 같은 operation 내부 | S3 서명 URL에 `PUT` |
| 업로드 검증·분석 접수 | 같은 operation 내부 | `POST /api/v1/receptions/{id}/recordings/{fileId}/completion` |
| 녹음 재생 URL 발급 | `receptions/recording-access` | `GET /api/v1/receptions/{id}/recordings/{fileId}/access` |

분석 시도 상태 조회는 호출 어댑터를 구현했으며 별도 시도 이력 화면은 추가하지 않았다. 화면의 현재 처리 상태와 결과는 접수 상세 재조회로 확인한다.

## 새로 구현한 요청과 화면 처리

| 화면 필드 | 백엔드 `finalData` 필드 | 방식 |
|---|---|---|
| 작업 유형 | `workType` | 외벽 누수→`EXTERIOR_LEAK`, 내벽 누수→`INTERIOR_LEAK`, 막힘→`BLOCKAGE`, 보일러→`BOILER`, 방수→`WATERPROOFING` |
| 증상 | `symptoms` | 문자열 |
| 방문 주소 | `address` | 문자열 |
| 고객 연락처 | `contactPhone` | 관리자 확인값. 마스킹된 발신번호를 대신 보내지 않음 |
| 방문 시작·종료 | `desiredVisitFrom`, `desiredVisitTo` | 날짜·시간 선택 후 UTC ISO 8601 전송 |
| 추가 요청·메모 | `notes` | 빈 메모도 최종값에 포함 |

실제 검토 DTO에 없는 고객명과 자유 형식 방문 시간은 이 API로 보내지 않는다. `transcript`는 STT 원문, `aiOriginal`은 최초 분석 원본, `finalData`는 최신 편집값으로 구분한다. 서버의 `reviewEditable`이 false이면 수정·검토 완료를 제공하지 않는다. 확정 취소는 확정 상태이며 의뢰가 등록되지 않은 경우 제공한다.

변경 요청에는 `expectedVersion`과 `Idempotency-Key` 헤더를 보낸다. 통신 오류 재시도에는 동일 요청 키를 유지하고 409 충돌에는 상세를 다시 읽은 뒤 새 키를 사용한다. 서버의 임의 오류 본문은 화면에 표시하지 않는다. 접수 결과만 기존 저장소에 병합하여 다른 업무 데이터를 덮어쓰지 않는다.

## 녹음 업로드와 재생

접수 상세에서 사용자가 WAV 원본 파일을 선택한다. 기존 Android Softphone 폴더 권한 연결은 유지하지만, 폴더 자동 탐색·감시는 추가하지 않았다.

1. 변경하지 않은 `originalFilename`, WAV MIME, 파일 크기를 전송한다. 프론트에서 전화번호·녹음 일시를 추출하지 않는다. 현재 백엔드의 실제 추출 범위는 아래와 같다.
2. 전체 바이트의 SHA256을 계산해 **digest 32바이트의 Base64**로 전달한다. hex 문자열이 아니다.
3. 업로드 예약 응답의 `uploadUrl`, `requiredHeaders`, `version`, `recordingFileId`를 사용한다.
4. S3에는 서명된 Content-Type·Content-Length·If-None-Match·SHA256 헤더를 유지해 PUT한다. 서비스 Bearer는 보내지 않는다. 예약 파일과 선택한 파일의 크기·해시가 다르면 전송하지 않는다.
5. 예약 후 버전으로 완료 통보하고 접수 상세를 다시 조회한다. 202 응답은 분석 접수이며 분석 완료로 표시하지 않는다.

재시도 정보는 세션 메모리에 유지한다. S3 전송 후 완료 통보만 실패하면 같은 완료 요청을 재시도한다. 앱 재시작 후에는 서버 상세의 미완료 파일 ID로 URL을 재발급받고 같은 원본 파일을 다시 선택한다. 업로드·재생 서명 URL은 AppStore나 디스크에 보관하지 않는다. 재생할 때마다 단기 URL을 발급받아 대화상자 안에서 사용한다.

백엔드 `WavRecording.contactPhone`의 현재 동작:

- `rec_표시명_번호1_번호2@YYMMDD_HHmmss.wav` 형식에서 두 번호를 읽는다.
- 두 번호의 하이픈을 제거하고, 두 값이 모두 유효하며 서로 같은 경우에만 연락처를 자동 채운다.
- 번호 위치에 이름이 있거나 두 번호가 다르면 자동 추출하지 않는다. 관리자 수동 확인이 필요하다.
- 파일명 날짜·시간을 파싱해 `callStartedAt`으로 저장하는 기능은 현재 없다. 해당 값은 접수 생성 요청으로 별도 전달할 수 있다.
- AI 상대 날짜 해석의 `referenceTime`은 파일명 시간이 아니라 서버에서 녹음 업로드를 최초 검증 완료한 시각이다.

전화번호 자동 추출 범위 확대와 파일명 일시 추출은 이번 프론트 연동에서 변경하지 않았다.

## 구현 파일별 역할

| 파일 | 담당 내용 |
|---|---|
| `shared/lib/src/reception_backend.dart` | 실제 REST 경로, 요청·응답 매핑, 서비스 Bearer, 버전·멱등성, SHA256 및 S3 업로드·재시도 |
| `shared/lib/src/receptions.dart` | 접수 목록·상세·페이지 이동, 검토 입력·확정/취소, WAV 선택 및 재생 |
| `shared/lib/src/integration.dart` | 접수 응답을 기존 저장소에 병합하고 재조회·충돌 처리를 연결 |
| `shared/lib/src/shell.dart` | 인증 세션 주입 시 접수 화면 진입, 해당 화면 재조회, 실제 서버 모드에서 미연동 메뉴 제외 |
| `shared/lib/src/admin.dart` | 실제 서버 모드에서 새 접수 화면으로 분기하고 개발 모드는 기존 화면 유지 |
| `shared/lib/allcheck_ui.dart` | 관리자 서버 연결 기본 어댑터 설정, 기사 SDK 전용 로그인 흐름 유지 |
| `shared/test/reception_backend_test.dart` | REST 계약·상태 매핑·업로드 재시도·세션 종료·화면 연결 테스트 |

## 후속 작업 순서

1. 백엔드 관리자 로그인·JWT 요청 인증 및 `ReceptionAdministratorIdentity` 구현을 main에 반영한다. 관리자 식별 구현이 없으면 접수 API도 503으로 거부된다.
2. 인증 어댑터에서 서비스 토큰을 받아 접수 어댑터에 연결한다.
3. 실제 저장소와 AI 환경을 준비한 뒤 접수 생성→녹음 업로드→STT·AI→검토 확정/취소를 통합 검수한다.
4. 기사 인증·기사 정보·의뢰·배차 등 실제 Controller가 반영되는 순서로 API를 연결한다.
5. 기업체의 가입 필수·선택 기준이 확정되면 프론트 입력 검증과 백엔드 DTO 조건을 함께 맞춘다.

## 두 앱의 상태와 미연동 항목

| 구분 | 적용 또는 유지 | 아직 연결하지 않은 부분 |
|---|---|---|
| 관리자앱 | 위 접수·녹음 API 어댑터 및 화면 | 관리자 로그인·JWT 요청 인증·관리자 식별, 의뢰 등록·배차·기사 관리·보고서·채팅·푸시 API |
| 기사앱 | 기존 카카오 SDK 로그인·닉네임·승인 대기·로그아웃·개발용 입장 유지 | 카카오 토큰의 서버 검증·서비스 토큰 발급, 기사 가입·프로필·배차·작업·정산·채팅 API |

현재 main의 `SecurityConfig`는 접수 API에 ADMIN을 요구하며 JWT 요청 인증 필터 연결이 TODO 상태다. **이 코드만으로 관리자 로그인부터 실서버 접수 조회까지의 검수를 완료할 수 없다.** 클라이언트가 관리자로 승인하거나 서버 인증을 우회하는 기능은 추가하지 않았다.

추후 인증 어댑터가 발급받은 **올체크 서비스 토큰**을 `SpringFrontendBackend.setServiceSession`에 전달하고 이 인스턴스를 `launchAllcheck`에 주입하면 관리자앱은 접수 목록으로 진입한다. 카카오 SDK 공급자 토큰을 대신 넣으면 안 된다. 테스트에서는 HTTP mock과 서비스 세션 주입으로 이 화면 연결을 확인한다.

기사앱은 접수 전용 어댑터(`SpringFrontendBackend`)를 사용하지 않는다. `API_BASE_URL`이 지정되면 main에 반영된 기사 인증·가입 계약(`DriverOnboardingBackend`)으로 연결하고, 미지정 시 기존 SDK 로그인 흐름을 유지한다. 의뢰·배차 등 나머지 기사 도메인은 반영되는 순서로 연결해야 한다. 기업체 결정 대기 중인 가입 필수·선택 항목은 변경하지 않았다. 기존 `HttpFrontendBackend`는 과거 API 초안용이며 기본 연결에는 사용하지 않는다.

## 실행과 검수

```powershell
# 저장소 루트: 기존 SDK 경로에 맞춰 Flutter 인자를 지정
./apps/frontend/scripts/bootstrap.ps1 -Flutter flutter

cd apps/frontend/shared
flutter test

# 관리자앱 UI 검수: 서버 인증 전에는 개발용으로 입장
cd ../admin
flutter run --debug

# 기사앱 SDK 로그인: 등록된 로컬 앱 키 사용
cd ../driver
flutter run --debug --dart-define=KAKAO_NATIVE_APP_KEY=YOUR_NATIVE_APP_KEY
```

인증이 준비된 서버를 연결할 때 관리자앱의 `API_BASE_URL`은 `https://서버호스트`처럼 **서버 origin만** 지정한다. `/api/frontend`나 `/api/v1`을 붙이지 않는다. debug에서만 localhost·127.0.0.1·10.0.2.2 HTTP를 허용하며 Android 네트워크 정책 설정은 별도다. 서비스 토큰·카카오 앱 키·녹음 원본을 커밋하지 않는다.

검수 항목:

- 두 앱의 개발용 진입과 기존 기사 SDK 로그인 유지
- 인증 반영 후 서버 접수 목록·페이지 이동·상세 조회
- AI 원본과 편집값 구분 및 편집 불가 상태 제한
- 방문 시작·종료 입력, 검토 확정·취소 후 서버 버전 반영
- WAV 원본 파일명 유지와 업로드·완료 통보 재시도
- 분석 대기·성공·실패 표시와 녹음 원본 재생
- 다른 관리자 접수에 대한 서버 접근 거부

## 검증 결과

- 공유 테스트 40개 통과. 실제 REST 경로·메서드·버전·멱등성 헤더·DTO 매핑, 원본과 편집값 분리, S3 업로드·완료 통보 재시도, 다른 파일 전송 차단, 로그아웃 중 응답 폐기 및 접수 화면 진입을 검증했다.
- 기존 기사 SDK 로그인과 관리자·기사 개발 진입 테스트도 통과했다. 실제 카카오 계정의 SDK 인증을 다시 수행한 것은 아니다.
- 관리자·기사 앱 분석에서 이슈 없음. 공유 패키지는 기존 정보성 진단 24건이 남아 있으며 오류·경고는 없다.
- 관리자·기사 Android debug APK 빌드 통과. Firebase 플러그인의 향후 Kotlin Gradle Plugin 호환성 안내가 출력되지만 빌드는 성공했다.
- `git diff --check` 통과.

실서버 인증과 실제 S3·STT·AI End-to-End 검수는 남은 서버 의존성 반영 후 수행한다. mock 계약 테스트를 실서버 검증으로 간주하지 않는다.
