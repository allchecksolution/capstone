# 백엔드 API 연동 및 검수 안내

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

1. 변경하지 않은 `originalFilename`, WAV MIME, 파일 크기를 전송한다. 파일명에서 전화번호·녹음 일시를 추출하는 역할은 백엔드에 둔다.
2. 전체 바이트의 SHA256을 계산해 **digest 32바이트의 Base64**로 전달한다. hex 문자열이 아니다.
3. 업로드 예약 응답의 `uploadUrl`, `requiredHeaders`, `version`, `recordingFileId`를 사용한다.
4. S3에는 서명된 Content-Type·Content-Length·If-None-Match·SHA256 헤더를 유지해 PUT한다. 서비스 Bearer는 보내지 않는다. 예약 파일과 선택한 파일의 크기·해시가 다르면 전송하지 않는다.
5. 예약 후 버전으로 완료 통보하고 접수 상세를 다시 조회한다. 202 응답은 분석 접수이며 분석 완료로 표시하지 않는다.

재시도 정보는 세션 메모리에 유지한다. S3 전송 후 완료 통보만 실패하면 같은 완료 요청을 재시도한다. 앱 재시작 후에는 서버 상세의 미완료 파일 ID로 URL을 재발급받고 같은 원본 파일을 다시 선택한다. 업로드·재생 서명 URL은 AppStore나 디스크에 보관하지 않는다. 재생할 때마다 단기 URL을 발급받아 대화상자 안에서 사용한다.

## 두 앱의 상태와 미연동 항목

| 구분 | 적용 또는 유지 | 아직 연결하지 않은 부분 |
|---|---|---|
| 관리자앱 | 위 접수·녹음 API 어댑터 및 화면 | 관리자 로그인·JWT 요청 인증·관리자 식별, 의뢰 등록·배차·기사 관리·보고서·채팅·푸시 API |
| 기사앱 | 기존 카카오 SDK 로그인·닉네임·승인 대기·로그아웃·개발용 입장 유지 | 카카오 토큰의 서버 검증·서비스 토큰 발급, 기사 가입·프로필·배차·작업·정산·채팅 API |

현재 main의 `SecurityConfig`는 접수 API에 ADMIN을 요구하며 JWT 요청 인증 필터 연결이 TODO 상태다. **이 코드만으로 관리자 로그인부터 실서버 접수 조회까지의 검수를 완료할 수 없다.** 클라이언트가 관리자로 승인하거나 서버 인증을 우회하는 기능은 추가하지 않았다.

추후 인증 어댑터가 발급받은 **올체크 서비스 토큰**을 `SpringFrontendBackend.setServiceSession`에 전달하고 이 인스턴스를 `launchAllcheck`에 주입하면 관리자앱은 접수 목록으로 진입한다. 카카오 SDK 공급자 토큰을 대신 넣으면 안 된다. 테스트에서는 HTTP mock과 서비스 세션 주입으로 이 화면 연결을 확인한다.

기사앱은 `API_BASE_URL`만으로 접수 전용 어댑터를 활성화하지 않으며 기존 SDK 로그인 흐름을 유지한다. 기사 도메인이 main에 반영되면 실제 Controller·DTO로 연결해야 한다. 기업체 결정 대기 중인 가입 필수·선택 항목은 변경하지 않았다. 기존 `HttpFrontendBackend`는 과거 API 초안용이며 기본 연결에는 사용하지 않는다.

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
