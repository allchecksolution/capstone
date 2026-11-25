# 백엔드 API 연동 및 검수 안내

## 최신 main 연동

- `API_BASE_URL`에는 서버 origin 또는 `/api/v1`까지 포함한 URL을 지정한다. 기사 앱의 카카오 SDK 초기화와 Android 콜백에는 별도로 `KAKAO_NATIVE_APP_KEY` 빌드 인자가 필요하다.
- 관리자 로그인, 기사 가입 심사, 관리자 계정 관리는 ACK-30과 ACK-37의 REST 계약을 사용한다. 동일한 관리자 서비스 세션으로 상담 접수, 녹음 업로드, STT 원문·AI 분석 결과 조회, 검토 확정과 의뢰 등록을 호출한다. 키 갱신과 계정별 자동 녹음 전송은 이 관리자 세션을 사용한다.
- 의뢰 등록은 확정된 접수 번호와 버전으로 서버 자동 배차를 시작한다. 관리자 작업목록과 최근 30일 통계, 기사 배차 요청 목록·수락·거절 및 내 작업 목록, 관리자·기사 채팅 조회·전송을 연결했다.
- STT와 AI는 백엔드 처리 흐름을 따르며 앱은 접수 상세에서 결과를 조회한다. 실제 서버·S3·AI 통합 검증과 작업 상태 변경·사진·보고서·취소 후속 처리의 프론트 연동은 남아 있다.

아래 섹션은 이전 시점의 구현·검수 기록이다.

## 2026년 11월 25일 로컬 추가 구현

아래 변경은 작업 폴더에만 적용한 상태이며 커밋·푸시하지 않았다.

### 사업자등록증 OCR과 가입 입력

- 기사 가입 2단계 사업자 정보 입력 화면에 촬영·사진 선택을 추가했다. Android의 한국어 ML Kit로 기기 내에서 처리하며 사진과 인식 원문을 서버로 업로드하지 않는다.
- 사업자등록번호·상호/법인명·대표자·사업장 주소·업태·종목을 인식 후보로 추출한다. 결과 확인 창에서 수정하고 선택한 항목만 반영한다. 기존 값이 있는 항목은 기본 선택하지 않는다.
- 사진 선택 취소·권한 거부·인식 실패·미지원 플랫폼에서는 기존 수동 입력을 계속 사용할 수 있다. OCR 자체를 필수 절차로 만들지 않았다.
- OCR 반영 시 기존 사업자 확인 상태를 해제한다. OCR은 실제 사업자 유효성 검증이나 관리자 승인 기능이 아니므로 별도 확인을 다시 진행한다.
- 가입 3단계의 기타 활동 지역·기타 장비 자유 입력을 제거했다. 기존 전문 분야·활동 지역·보유 장비 선택은 유지하고, 이전 임시 입력에 남은 기타 값도 다음 단계 진행 시 제거한다.
- 한국어 OCR은 Android만 지원한다. iOS·웹에서는 수동 입력을 사용한다. 촬영 후 Android가 앱을 종료한 경우 다음 OCR 실행에서 image_picker의 이전 사진 복구를 시도한다.

### Softphone 녹음 자동 수집

- Android 관리자앱에서 `Music/Softphone/recordings`의 SAF 읽기 권한을 받아 자동 수집을 켠다. 기본은 켜기 이후 새 파일만 수집하며, 기존 파일 포함 여부를 사용자가 선택한다.
- 앱 사용 중 약 10초 간격으로 확인하고, 크기·수정 시각이 안정된 파일의 RIFF/RIFX WAVE 헤더 선언 크기를 확인한 뒤 대기 목록에 추가한다. 통화 종료 이벤트 자체를 감지하는 방식은 아니다.
- 앱이 백그라운드이거나 프로세스가 종료된 동안에는 WorkManager 예약 작업이 확인한다. 정기 간격은 최소 15분이며 Android 절전 정책에 따라 지연된다. 설정의 앱 강제 종료 중에는 실행을 보장하지 않는다.
- 수집 목록은 앱 내부 설정에 저장한 원본 파일 참조다. 원본을 삭제·이동하면 전송할 수 없으며, 외부 공유 폴더나 별도 공개 위치에 원본을 복사하지 않는다. 앱은 원본을 삭제하지 않는다.
- 자동 전송은 파일당 64MB 이하의 완성된 RIFF/RIFX WAV를 지원한다. RF64/BW64 자동 수집은 이번 범위에서 제외했다. 기록 중·크기가 맞지 않는 파일은 보류한다.
- 인증된 관리자앱을 열면 접수 생성→기존 녹음 업로드 API→완료 검증을 한 파일씩 순서대로 실행한다. 서비스 토큰을 디스크에 저장하지 않으므로 종료 중 자동 서버 업로드는 하지 않는다.
- 접수 생성·업로드 요청 키와 진행 중 접수 ID를 저장하여 앱 재시작과 통신 오류에서 같은 작업을 재개한다. 같은 파일명·같은 내용의 메타데이터만 바뀐 경우 중복 전송하지 않는다. 다른 관리자가 맡은 미완료 작업을 현재 계정으로 재전송하지 않는다.
- 인증 연동 시 `setServiceSession(serviceToken, administratorId: stableAdministratorId)`로 서버 origin과 함께 구분할 **안정적인 관리자 식별자**도 전달해야 한다. 공급자 토큰 또는 임의 ID로 서버 권한을 대신하지 않는다. 인증이 없거나 개발용 입장 상태이면 로컬 수집만 하고 전송 대기한다.
- 자동 수집을 끄거나 폴더 연결을 해제하면 이후 예약 수집을 중단한다. 수집 목록과 업로드 진행 기록은 유지하며 원본 파일은 보존한다.

### 로고와 구현 파일

사용자 제공 첫 번째 이미지는 관리자앱, 두 번째 이미지는 기사앱 로고로 연결했다. 로그인 화면·공통 파란색 테마 및 Android/iOS 앱 아이콘 자산에 반영했다. 원본 복사본은 임시 `UI_Design/logos`에, 실제 앱 자산은 `shared/assets/branding`에 둔다. 화면 구조·카카오 SDK 로그인 방식은 유지한다. iOS 아이콘은 생성했지만 iOS 빌드는 Windows에서 검증하지 않는다.

| 파일 | 역할 |
|---|---|
| `shared/lib/src/business_document.dart` | 사진 선택·복구, 네이티브 OCR 호출, 사업자 항목 후보 추출 |
| `shared/lib/src/business_ocr.dart` | 2단계 OCR 확인·수정·반영 및 수동 입력 유지 |
| `driver/android/.../MainActivity.kt` | 한국어 ML Kit 기기 내 OCR |
| `admin/android/.../RecordingCollector.kt`, `RecordingScanWorker.kt` | SAF 폴더 검사·완성 파일 판별·수집 목록 및 예약 작업 |
| `shared/lib/src/recording_collection.dart` | 대기 파일의 서버 전송·작업 기록·세션별 재개 |
| `shared/lib/src/recording_settings.dart` | 수집 설정·목록·전송 재시도 화면 |

아래 11월 20일 기준 설명 중 ‘자동 탐색·감시 미구현’ 항목은 위 로컬 추가 구현으로 변경되었다. 실제 서비스 인증과 S3·AI 전체 연동 검증은 여전히 별도다.

### 11월 25일 검증과 남은 확인

- Flutter 전체 테스트 52개 통과: OCR 항목 추출, 2단계 결과 반영·기존 값 보호·실패 후 수동 입력, 3단계 기타 항목 제거, 수집 파일 중복 방지·재시작·세션 변경, 기존 로그인과 업무 테스트를 포함한다.
- Android WAV 판별 단위 테스트 3개 통과: 완성된 RIFF/RIFX, 기록 중 헤더, 잘못된 파일을 확인했다.
- 관리자·기사 Android debug APK 빌드 성공. 관리자·기사 앱 분석 이슈 없음. 공유 패키지는 오류·경고 없이 정보성 진단 25건이 남아 있다.
- 한국어 ML Kit 모델과 네이티브 호출 코드는 컴파일했다. 가상 사업자등록증 이미지를 쓰는 기기 테스트도 추가했으나, 에뮬레이터가 Android 버전을 응답하지 않고 이후 ADB offline 상태가 되어 실제 OCR 기기 테스트는 실행하지 못했다. 실제 촬영·사진 선택의 인식 정확도도 검수 대기다.
- SAF 권한 선택, 실제 Softphone이 작성 중인 파일의 수집 시점, 앱 종료·재부팅 이후 WorkManager 실행은 정상 연결된 Android 기기에서 추가 검수해야 한다. 실제 서버 업로드는 서비스 인증·관리자 식별자·저장소 환경 반영 후 검수한다.
- iOS 아이콘 자산은 생성했지만 iOS 빌드는 수행하지 않았다. iOS 한국어 OCR과 녹음 자동 수집은 제공하지 않는다.

참고: [Android ML Kit 한국어 문자 인식](https://developers.google.com/ml-kit/vision/text-recognition/v2/android), [Android 예약 작업의 최소 주기](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [image_picker](https://pub.dev/packages/image_picker).

## 구현 여부 요약

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
