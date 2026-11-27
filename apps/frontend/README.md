# 관리자 앱, 기사앱

앱 소스는 `apps/frontend`에 있습니다. 개발 도구는 Flutter이며 관리자앱, 기사앱, 공통 UI 및 서비스 어댑터로 구성합니다.

## 실행

Flutter 3.50 이상, Dart 3.10 이상, Android SDK와 JDK 17을 사용합니다. iOS는 macOS/Xcode가 필요합니다.

저장소 루트에서 의존성과 로컬 SDK 경로를 준비합니다.

```powershell
./apps/frontend/scripts/bootstrap.ps1 -Flutter flutter
./apps/frontend/scripts/check.ps1 -Flutter flutter
```

관리자앱은 `apps/frontend/admin`, 기사앱은 `apps/frontend/driver`에서 실행합니다.

```powershell
flutter run --debug --dart-define=ALLCHECK_DEV_ACCESS=true
```

debug 빌드에서는 기본으로 로그인 화면에 ‘개발용으로 입장’을 표시합니다. `--dart-define=ALLCHECK_DEV_ACCESS=false`로 숨길 수 있습니다. 개발용 입장 중 변경한 데이터는 저장하지 않습니다. profile/release에서는 동작하지 않습니다.

기사 가입 정보 입력 화면의 ‘개발용: 승인 대기 건너뛰기’는 같은 debug 옵션으로 표시합니다. 가입 신청을 완료한 승인 대기 화면에는 ‘임시로 관리자 검토 건너뛰기’를 검토 안내 바로 아래에 표시합니다. 이 버튼은 debug/profile/release에서 모두 기본 표시하며 `ALLCHECK_DEV_ACCESS`와 무관합니다. 임시 기능을 끄려면 빌드 시 `--dart-define=ALLCHECK_TEMP_REVIEW_ACCESS=false`를 지정합니다. 입력한 프로필을 사용해 임시 홈으로 이동하며, 실제 서버의 가입 신청이나 승인 상태는 변경하지 않습니다. 임시 홈에서는 서버 업무 요청과 상태 동기화를 수행하지 않으며 로그아웃 시 임시 상태를 초기화합니다. 기존 설치 앱에는 다시 빌드하여 설치해야 변경이 반영됩니다.

서버를 연결하는 실행 예시:

두 앱은 같은 EC2 주소 `https://api.allcheck.cloud`를 사용합니다. `.env`가 없으면 `apps/frontend/.env.example`을 `.env`로 복사합니다. 기존 파일이 있으면 카카오 키 등은 유지하고 API 주소·개발용 진입 옵션만 변경합니다. `.env`는 Git에서 제외하며, 각 앱 디렉터리에서 아래 명령으로 실행합니다.

```powershell
flutter run --dart-define-from-file=../.env
```

`API_BASE_URL`에는 서버 origin을 지정합니다. 관리자·기사·상담 접수 어댑터가 `/api/v1`을 한 번만 붙이며, 기존 `/api/v1` 포함 설정도 호환됩니다. 원격 서버는 HTTPS만 허용하고, 로컬 debug에서는 `http://127.0.0.1:8080` 또는 Android 에뮬레이터용 `http://10.0.2.2:8080`을 사용할 수 있습니다. 실기기의 `127.0.0.1`은 기기 자신이며 PC 백엔드에 연결하려면 별도로 `adb reverse`가 필요합니다.

현재 기본 어댑터는 관리자 `AdminRestBackend`(로그인·기사 심사·관리자 계정·상담 접수), 기사 `DriverOnboardingBackend`(카카오 서비스 인증·가입·승인 상태 조회)입니다. 관리자 앱은 `SpringFrontendBackend.withSession`으로 상담 접수 어댑터에 같은 로그인 세션을 연결하며, 홈의 상담 접수 버튼으로 진입합니다. 보호된 요청은 토큰 만료 시 한 번 갱신 후 원래 요청을 재시도합니다. 배차·작업·채팅 연동은 후속 작업이며, 실제 계정 로그인·업무 왕복은 별도 검수가 필요합니다. 실제 서버 검수용 `.env`에서는 `ALLCHECK_DEV_ACCESS=false`, `ALLCHECK_TEMP_REVIEW_ACCESS=false`로 두어 개발용 입장과 임시 승인 건너뛰기를 숨깁니다. [진행 단계·연동 내역·검수 방법](BACKEND-INTEGRATION.md)을 확인하세요.

## 연결 준비

### Android Firebase 설정

같은 Firebase 프로젝트에 관리자 Android 앱(`kr.allcheck.flutter.admin`)과 기사 Android 앱
(`kr.allcheck.flutter.driver`)을 각각 등록한다. 각 앱에서 다운로드한 `google-services.json`을
`apps/frontend/admin/android/app/`과 `apps/frontend/driver/android/app/`에 각각 넣는다.
프로젝트 ID만으로 이 JSON 파일을 만들 수 없으며, 설정 파일은 Git에서 제외한다.

프로젝트 ID를 입력할 Terraform·백엔드 로컬 파일과 실행 방법은
[Android 로컬 클라우드 설정](../backend/docs/local-cloud-setup.md#android만-사용하는-경우)을 따른다.
각 앱 실행 시 `--dart-define=ALLCHECK_PUSH_ENABLED=true`를 추가한다.

### 백엔드 없이 카카오 로그인

기사앱은 `API_BASE_URL` 없이도 카카오 SDK로 로그인할 수 있습니다. 카카오 인증 후 `계속`을 누르면 가입 정보 입력 1단계로 이동하며, 신청 전 상태를 승인 대기로 표시하지 않습니다. 카카오 닉네임과 이메일은 가입 정보에 미리 채우고, 1단계에서 저장한 이름은 계정별 앱 표시 이름으로 기억합니다. 카카오 계정 닉네임은 변경하지 않습니다. 가입 화면에서 로그아웃할 수 있습니다. 서버 미연결 상태에서는 사업자 확인·가입 신청을 완료한 것으로 처리하지 않습니다. 실제 관리자 승인 대기는 서버가 신청을 접수한 뒤에만 표시하며 실제 가입 신청·관리자 승인·배차에는 서버 연결이 필요합니다. 개발용 버튼을 통한 임시 체험을 제외하면 승인 전 업무 화면 진입을 허용하지 않습니다. 앱을 다시 시작하면 카카오 로그인을 다시 진행합니다.

카카오디벨로퍼스에서 카카오 로그인을 활성화하고 Android 패키지명 `kr.allcheck.flutter.driver`와 서명 인증서의 키 해시를 등록한 뒤, `apps/frontend/driver`에서 실행합니다.

```powershell
flutter run --dart-define=KAKAO_NATIVE_APP_KEY=YOUR_NATIVE_APP_KEY
```

iOS에서는 번들 ID 등록 및 `ios/Flutter/Kakao.local.xcconfig`의 키도 설정합니다. 카카오톡 설치·미설치 로그인, 취소, 홈 진입, 로그아웃을 각각 확인하세요. 실제 앱 키와 플랫폼 등록 없이는 실기기 인증을 완료할 수 없습니다.

설정 참고: https://developers.kakao.com/docs/ko/flutter/getting-started

[실제 API 연동 문서](BACKEND-INTEGRATION.md)가 현재 main의 Controller·DTO에 맞춘 연결 기준입니다. [기존 연결 초안](API-CONTRACT.md)의 operation URL과 전체 state 응답은 실제 서버 구현을 뜻하지 않습니다. `HttpFrontendBackend`는 초안용으로 유지하며 기본 서버 연결에는 사용하지 않습니다.

알림은 분석 완료/실패, 배차 요청/상태 변경, 작업 상태, 채팅, 기사 승인 상태를 구분합니다. 앱 실행 중에는 인앱 배너를 표시하고, 백그라운드·종료 상태에서 알림을 누르면 인증 후 서버에서 최신 상태를 가져와 이동합니다. 알림 설정에서 권한을 요청하고 기기 토큰을 서버에 등록합니다.

Android APK는 저장소 루트에서 `./apps/frontend/scripts/build-apks.ps1 -Flutter flutter`로 빌드합니다. 스크립트는 `apps/frontend/.env`를 두 앱에 공통으로 전달하며 파일이 없으면 빌드 전에 중단합니다. 다른 설정 파일은 `-DefinesFile <경로>`로 지정합니다. 주소를 변경하면 다시 빌드·설치해야 합니다. Firebase 설정 파일과 로컬 카카오 키 설정은 커밋하지 않습니다. Flutter 템플릿 라이선스, 테스트, 네이티브 호스트와 각 플랫폼 빌드 파일은 앱 실행에 필요하므로 유지합니다.

### 안드로이드 휴대폰에 설치

현재는 사용자 요청에 따라 중간 APK 빌드·반복 설치를 보류합니다. 오류를 모아 코드와 자동 테스트로 먼저 수정·검증하고, 4·5단계 연동과 6단계 사전 검증 후 최종 APK를 한 번 생성해 실기기에서 확인합니다. 이미 만들어 둔 `stage3-r2` APK를 지금 설치할 필요는 없습니다. [오류 및 최종 재검수 목록](BACKEND-INTEGRATION.md#오류-및-최종-재검수-목록)에 보류 항목을 기록합니다.

PC의 APK를 새로 빌드해도 휴대폰에는 자동 반영되지 않으므로 새 파일로 업데이트 설치해야 합니다. 현재 재검수용은 **0.3.1(빌드 4)**입니다. `./apps/frontend/scripts/build-apks.ps1 -BuildLabel stage3-r2`로 기본 APK와 함께 구분 가능한 `apps/frontend/allcheck-admin-stage3-r2-flutter-debug.apk`, `apps/frontend/allcheck-driver-stage3-r2-flutter-debug.apk`를 생성합니다. 두 파일을 USB 등으로 휴대폰에 옮기고 ‘내 파일’에서 각각 열어 기존 앱 위에 업데이트 설치합니다. 설치 출처 허용을 요청하면 파일을 연 앱에 허용합니다. 한 휴대폰에 두 앱을 모두 설치할 수 있습니다. 인터넷 연결 후 같은 계정으로 다시 로그인하며 API 주소는 이미 빌드에 포함돼 있습니다. [실기기 검수 순서](BACKEND-INTEGRATION.md#안드로이드-실기기에서-확인하기)를 따릅니다.

## 검증 상태

ACK-41 후속 보완을 포함한 공유 패키지 전체 테스트 92개가 통과했습니다. 사용자가 안드로이드 실기기에서 두 앱의 설치·로그인, 관리자 기사·상담 목록 조회, 기사 사업자 확인·가입 신청, 관리자 승인 후 기사 홈 진입, 상담 생성 후 상세·목록 재조회, WAV 업로드 후 STT 원문·AI 결과 표시와 녹음 원본 재생을 확인했습니다. 입력칸이 이전 빈 값을 유지하던 문제를 프론트에서 보완했으며, 실기기 재검수는 6단계 최종 APK로 모읍니다. 검토 확정/취소·푸시·iOS 동작도 별도 검증이 필요합니다. 현재 결과와 남은 항목은 [진행 단계·실기기 검수 기록](BACKEND-INTEGRATION.md)을, 이전 작업의 결과는 [과거 검증 기록](VERIFICATION.md)을 참고하세요.
