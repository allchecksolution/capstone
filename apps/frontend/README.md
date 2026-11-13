# ALLCHECK Flutter 프론트

관리자앱과 기사앱의 Dart/Flutter 소스입니다. 앱 프론트는 이 폴더의 Flutter 구현만 사용합니다. 중복된 웹 및 Java WebView 구현은 제거했습니다. Android 실행에 필요한 Flutter 네이티브 호스트 코드는 포함합니다.

## 현재 상태

**실행 검증 전 소스입니다.** 이 PC에서는 조직의 Device Guard가 Dart 실행을 차단하여 의존성 설치, Flutter 분석, 테스트, Android/iOS/Web 빌드를 완료하지 못했습니다. 기존 APK는 기존 WebView 버전이며 Flutter APK가 아닙니다. 자세한 범위는 [VERIFICATION.md](VERIFICATION.md)를 참고하세요.

## 구성

- `admin`: 관리자 실행 앱. Android ID `kr.allcheck.flutter.admin`.
- `driver`: 기사 실행 앱. Android ID `kr.allcheck.flutter.driver`.
- `shared`: 공통 UI, 로컬 상태 모델, 플랫폼 연결, 워크플로 테스트.
- `shared/assets/flows.json`: 관리자 127개, 기사 117개 Figma 프레임 목록. 목록에 있다는 사실만으로 개별 화면의 완전한 재현을 의미하지 않습니다.

기존 앱과 설치 ID 및 로컬 저장 키가 다르므로 기존 앱의 데이터는 자동 이전되지 않습니다. 예시 데이터 주입 및 상태 미리보기 UI는 제거했습니다.

## 실행

두 앱의 개발용 화면 진입은 각 앱 폴더에서 `flutter run --debug --dart-define=ALLCHECK_DEV_ACCESS=true`로 켭니다. 로그인 화면의 ‘개발용으로 입장’을 사용합니다. 일반 실행과 profile/release에는 진입 버튼이 없으며, 개발 데이터는 메모리에만 보관합니다. 자세한 내용은 [ACK-22 인계 문서](ACK-22-HANDOFF.md)를 참고하세요.

Flutter 3.50 이상, Dart 3.10 이상을 사용할 수 있는 개발 환경이 필요합니다. Android에는 SDK와 JDK 17, iOS에는 macOS와 Xcode 및 서명 설정이 필요합니다. 조직 정책 차단은 관리자에게 해제를 요청해야 합니다.

아래 명령은 저장소 루트에서 실행합니다.

```powershell
./apps/frontend/scripts/bootstrap.ps1 -Flutter flutter
./apps/frontend/scripts/check.ps1 -Flutter flutter
cd apps/frontend/admin
flutter run
```

기사 앱은 `apps/frontend/driver`에서 `flutter run`을 실행합니다. 웹 실행은 각 앱에서 `flutter run -d chrome`을 사용합니다. Android 디버그 APK는 저장소 루트에서 다음 명령으로 생성합니다.

```powershell
./apps/frontend/scripts/build-apks.ps1 -Flutter flutter
```

`bootstrap.ps1`은 패키지 의존성과 로컬 SDK 경로를 준비합니다. `check.ps1`은 공유 패키지 분석/테스트 및 두 앱의 분석/웹 빌드를 수행합니다. 최초 성공 후 생성되는 앱별 `pubspec.lock`도 검토하여 커밋해야 합니다.

## 구현과 연동 경계

관리자 접수 검토·수동 입력·배차·기사 승인·보고서 수정 이력·취소 처리·통계·내보내기, 기사 가입·배차 응답·작업 단계·사진 보고서·재방문·취소·채팅 재시도·월별 통계를 로컬 상태로 구현했습니다. ver2와 겹치는 동작은 ver2를 우선하고 기존 고유 동작은 유지하는 방향입니다. 원본 비교 자료는 [기존 흐름 대조표](../FRONTEND_FLOW_AUDIT.md)를 참고하세요.

기사앱의 카카오 SDK 인증 코드와 Android/iOS 콜백 설정을 추가했습니다. 앱 키 설정 및 실기기 인증 검증은 남아 있습니다. 서버 계정 확인은 별도의 콜백으로 연결하며, 미연결 시 업무 화면에 진입하지 않습니다. 관리자 고정 MFA와 기사 로그인/승인 우회는 제거했습니다. 관리자 인증·사업자 검증·서버 배차·채팅·푸시는 미연결입니다. 설정과 백엔드 연결 지점은 [ACK-22 인계 문서](ACK-22-HANDOFF.md)를 참고하세요.

Android의 Softphone 설정은 사용자가 선택한 `Music/Softphone/recordings` 폴더에 대한 지속 읽기 권한을 요청하는 단계입니다. 통화 종료 감지·자동 수집·서버 업로드는 구현하지 않았습니다. 폴더 공개 여부와 기기 정책에 따라 접근 가능 여부가 달라집니다. iOS와 웹에서는 다른 앱의 전용 저장공간에 접근할 수 없습니다.

## Git

main에 직접 올리지 않습니다. 이 작업의 대상 브랜치는 `feat/ACK-22-admin-app-demo`입니다. Git 작성자 정보와 GitHub 인증이 준비된 뒤 소스만 커밋/푸시해야 합니다. 녹음 파일, SDK, APK, 빌드 캐시, 로컬 설정은 포함하지 않습니다.
