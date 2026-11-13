# 관리자앱, 기사앱

ACK-22 브랜치: `feat/ACK-22-admin-app-demo`

개발 환경에서 두 앱의 화면을 확인하려면 각 앱 폴더에서 아래 명령으로 실행합니다.

```powershell
flutter run --debug --dart-define=ALLCHECK_DEV_ACCESS=true
```

로그인 화면의 ‘개발용으로 입장’을 누르면 카카오 키나 서버 없이 홈으로 이동합니다. 기사앱은 메모리 안에서만 활성 상태를 만들며, 예시 업무 데이터는 추가하지 않습니다. 개발 옵션 실행에서는 별도 메모리 저장소를 사용해 일반 실행의 저장 데이터와 분리합니다. 앱을 완전히 다시 실행하면 개발 데이터는 초기화됩니다. 로그아웃 후에는 로그인 화면으로 돌아갑니다.

기본값은 비활성이고 `kDebugMode`도 검사하므로 profile/release 빌드에서는 옵션을 주어도 개발용 진입이 표시되거나 동작하지 않습니다. 기존 프로토타입·AI 안내 문구를 되살리지 않습니다. 일반 실행의 인증 제한은 그대로 유지합니다.

개발 진입 검증은 공유 패키지에서 `flutter test test/development_access_test.dart`와 `flutter test --dart-define=ALLCHECK_DEV_ACCESS=true test/development_access_test.dart`를 각각 실행합니다. 현재 PC의 Device Guard 차단으로 이 테스트는 실행하지 못했습니다.

앱 소스는 `apps/frontend`의 Flutter 구현으로 통일했습니다. 중복된 기존 웹/Java WebView 앱 두 폴더는 제거했습니다. 이전에 생성한 WebView APK에는 이번 변경이 적용되지 않으므로 Flutter 소스로 새로 빌드해야 합니다.

## 기사앱

`shared/lib/src/auth.dart`에서 공식 `kakao_flutter_sdk_user` 2.1 SDK를 사용합니다. 카카오톡이 있으면 카카오톡 로그인을 시도하고, 실행 실패 시 카카오계정으로 전환합니다. 사용자 취소는 로그인 화면을 유지합니다. 중복 요청을 막고 SDK 오류와 토큰을 화면이나 로그에 출력하지 않습니다. 로그아웃은 SDK 토큰도 정리합니다.

Android의 인증 콜백 Activity와 카카오톡 조회 설정, iOS의 URL scheme과 카카오톡 실행 허용 목록을 추가했습니다. 웹 카카오 로그인은 이번 범위에서 지원하지 않습니다.

Android 실행 예시(기사앱 폴더에서):

```powershell
flutter pub get
flutter run --dart-define=KAKAO_NATIVE_APP_KEY=YOUR_NATIVE_APP_KEY
```

Android manifest scheme도 같은 `dart-define`에서 생성합니다. 카카오 디벨로퍼스에 `kr.allcheck.flutter.driver`와 실제 빌드 서명의 키 해시를 등록하고 카카오 로그인을 활성화해야 합니다. 배포 서명 키 해시는 디버그 키 해시와 별도입니다.

iOS에서는 macOS에서 `driver/ios/Flutter/Kakao.local.xcconfig.example`을 같은 폴더의 `Kakao.local.xcconfig`로 복사하고 키를 입력합니다. Dart 실행 설정에도 동일한 키를 전달합니다. 실제 번들 ID `kr.allcheck.flutter.driver` 등록과 Xcode 서명이 필요합니다. 로컬 키 설정 파일은 Git에서 제외했습니다. Admin 키나 Client Secret을 앱에 넣지 않습니다.

설정과 SDK 흐름의 기준: [카카오 Flutter 시작하기](https://developers.kakao.com/docs/ko/flutter/getting-started), [카카오 로그인](https://developers.kakao.com/docs/ko/kakaologin/flutter).

## 백엔드 연결 지점

`AllcheckApp.onKakaoAuthenticated`는 `Future<Json?> Function(KakaoIdentity)` 콜백입니다. 백엔드 담당자가 기사 진입점에 어댑터를 연결해야 합니다. 전달되는 액세스 토큰은 서버에서 카카오에 검증하고 서비스 세션을 발급해야 합니다. 클라이언트가 보낸 사용자 ID나 로컬 계정 상태로 승인 여부를 판단하지 않습니다.

콜백은 신규 회원이면 `null`, 기존 회원이면 서버가 확인한 `{'status': '활성|승인 대기|반려|탈퇴', 'profile': {...}, 'reason': '...'}` 구조를 반환합니다. 실제 HTTP 경로와 DTO는 아직 합의하거나 구현하지 않았습니다. 서비스 토큰의 보관·갱신은 백엔드 계약에 맞춰 별도로 연결해야 합니다.

기본 실행에서는 카카오 인증 성공 화면까지만 진행합니다. 서버 어댑터가 없을 때 ‘계속’은 서비스 이용 불가 메시지를 표시하고 업무 화면에 들어가지 않습니다. 기존 기기의 활성 계정으로 우회하지 않습니다. 서버 계정 확인 후에는 이전 기기의 업무 상태를 초기화하고 서버 상태에 따라 가입 화면·승인 대기·홈으로 이동합니다.

관리자 로그인에 있던 임의 비밀번호 통과와 고정 MFA 코드 `123456`도 제거했습니다. 현재 관리자 로그인은 서버 인증 연동 전이므로 성공 처리하지 않습니다. 관리자 인증/MFA·권한, 기사 가입 제출·승인 갱신, 사업자 검증, 배차·채팅·보고서·사진 업로드·푸시 등은 백엔드 담당자가 연결해야 합니다. 나머지 업무 모델은 여전히 로컬 저장 방식이며 실제 서버 전송이 되지 않습니다.

## 화면 정리

앱 이름은 ‘관리자앱’, ‘기사앱’으로 통일했습니다. 예시 데이터 주입, 기사 화면 바로 진입, 기기 가입 상태로 로그인, 승인/반려 미리보기, 조회 권한 변경, 실패 재현, 흐름 대조 메뉴, 배차 결과 선택, 예시 위치 갱신과 미연결 Apple 버튼을 제거했습니다. 프로토타입 및 로컬 시연 안내 문구를 업무 화면에서 제거했습니다.

관리자 상담 접수에서 STT·AI 결과 항목을 제거했습니다. 기존 ‘분석 완료/분석 실패’ 상태는 화면에서 ‘접수 완료/정보 보완 필요’로 표시합니다. 필터와 상세 상태에도 같은 표시 규칙을 적용했습니다. 상담 접수·증상 입력·검토 기능은 유지합니다. 기존 데이터/테스트용 도메인 필드는 호환성을 위해 보존합니다.

## 검증

2026-11-13: `node apps/frontend/scripts/check-structure.mjs`, Android manifest와 iOS plist XML 파싱, `git diff --check` 확인. 구조 검사는 Dart 문법이나 SDK 호환성을 보증하지 않습니다.

이 PC에서는 Dart 실행이 조직 Device Guard에 차단됩니다. 패키지 설치, 분석, 위젯 테스트, APK/iOS 빌드와 실제 카카오 인증은 검증하지 못했습니다. 실제 앱 키도 제공받지 않았습니다. 추가한 `shared/test/auth_test.dart`에는 취소·중복 요청·실패·로컬 승인 우회 방지·서버 승인 대기·로그아웃 테스트가 있으며, 실행 가능한 환경에서 반드시 실행해야 합니다.

팀원 환경에서 저장소 루트의 `apps/frontend/scripts/bootstrap.ps1`, `apps/frontend/scripts/check.ps1`로 검증하고 생성된 앱별 lockfile을 검토해 커밋하세요. Android에서는 카카오톡 설치/미설치, 취소, 재로그인, 네트워크 실패, 계정 전환, 로그아웃을 실기기에서 확인하세요. iOS는 별도로 빌드와 동일한 인증 흐름 확인이 필요합니다.
