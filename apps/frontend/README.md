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

이 옵션은 debug 빌드에서만 로그인 화면에 ‘개발용으로 입장’을 표시합니다. 개발 데이터는 메모리에만 보관하며 일반 인증/서버 상태와 분리합니다. profile/release에서는 동작하지 않습니다.

서버를 연결하는 실행 예시:

```powershell
flutter run --dart-define=API_BASE_URL=https://YOUR_SERVER/api/frontend --dart-define=ALLCHECK_PUSH_ENABLED=true --dart-define=KAKAO_NATIVE_APP_KEY=YOUR_NATIVE_APP_KEY
```

관리자앱은 아이디·비밀번호로 로그인합니다. 공개 관리자 회원가입 및 2차 인증 화면은 없습니다. 최초 관리자 계정 발급은 서버에서 처리합니다. 기사앱은 카카오 SDK 인증 후 서버가 검증한 계정 상태로 가입·승인 대기·홈을 구분합니다. 연결 정보가 없으면 실제 로그인/서버 업무를 성공으로 처리하지 않습니다.

## 연결 준비

[서버 연결 계약](API-CONTRACT.md)에 작업별 경로, 응답, 알림 데이터, Firebase 준비와 요구사항별 범위를 정리했습니다. 이 계약은 프론트 어댑터의 연결 기준이며 이미 구현된 백엔드 API를 뜻하지 않습니다. 실제 서버 규격이 다르면 `FrontendBackend` 어댑터를 교체합니다.

알림은 분석 완료/실패, 배차 요청/상태 변경, 작업 상태, 채팅, 기사 승인 상태를 구분합니다. 앱 실행 중에는 인앱 배너를 표시하고, 백그라운드·종료 상태에서 알림을 누르면 인증 후 서버에서 최신 상태를 가져와 이동합니다. 알림 설정에서 권한을 요청하고 기기 토큰을 서버에 등록합니다.

Android APK는 저장소 루트에서 `./apps/frontend/scripts/build-apks.ps1 -Flutter flutter`로 빌드합니다. Firebase 설정 파일과 로컬 카카오 키 설정은 커밋하지 않습니다. Flutter 템플릿 라이선스, 테스트, 네이티브 호스트와 각 플랫폼 빌드 파일은 앱 실행에 필요하므로 유지합니다.

## 검증 상태

Node 구조 점검, XML 파싱 및 Dart formatter 문법 검사는 통과했습니다. Dart 실행 차단은 해제됐으나 실행 환경에서 pub.dev에 연결하지 못해 의존성을 설치하지 못했습니다. Dart 분석, 테스트, 새 APK/iOS 빌드 및 실제 서버·푸시 동작은 검증하지 못했습니다. [검증 기록](VERIFICATION.md)을 참고하세요.
