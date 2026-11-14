# 검증 기록

## 2026-11-14 카카오 SDK 단독 로그인 수정본

- 서버 주소가 없어도 카카오 SDK 인증 후 `계속`을 누르면 기사앱 홈에 진입합니다.
- SDK 인증 상태는 `카카오 인증`으로 구분하며 기사 가입 승인을 부여하지 않습니다. 서버 연결 시에는 기존 서버 인증·승인 흐름을 유지합니다.
- 인증 취소·중복 요청 방지, 이전 사용자 정보 초기화, 토큰 저장 제외, 로그아웃, 서버 승인 대기 유지 등 인증 테스트 4개와 전체 공유 테스트 26개 통과.
- Node 구조 검사 및 Git 공백 검사 통과.
- 실제 네이티브 앱 키를 적용한 실기기 로그인은 아직 미검증입니다. 카카오 콘솔 등록만으로 앱 실행 검증이 완료되지는 않습니다.

2026-11-13 기준입니다.

| 항목 | 상태 |
|---|---|
| 두 앱 진입점·공유 패키지·Dart part·네이티브 채널·iOS/Web 구조 | Node 구조 점검 통과 |
| Android manifest·iOS plist/entitlements | XML 파싱 확인 |
| Git 공백 검사 | 통과 |
| Dart 실행 | 정책 변경 후 Dart 3.14.0 실행 확인 |
| Dart 소스 문법 | formatter 파서로 앱 진입점·공유 코드·테스트 19개 파일 통과 |
| 의존성 설치 | 사용자 환경에서 공유 패키지와 관리자/기사 앱 설치 완료 |
| Flutter 분석 | 두 실행 앱은 이슈 없음. 공유 패키지는 오류·경고 없이 정보성 진단 23개(기존 API deprecated 및 스타일), `--no-fatal-infos` 통과 |
| 공유 테스트 | 26개 통과 |
| 개발 진입 옵션 테스트 | 2개 통과 |
| Android/iOS/Web 실행 | Android 엔진 다운로드가 실행 환경의 네트워크 제한에 막혀 미완료. iOS/Web 미검증 |
| 실제 서버 인증·배차·FCM/APNs·위치·파일 전송 | 서버 계약/프로젝트 설정 및 실기기 검증 필요 |
| 에뮬레이터 최신 Flutter 앱 실행 | Android 엔진 캐시 및 ADB 사용자 폴더 권한 제한으로 미완료 |

구조 검사는 `node apps/frontend/scripts/check-structure.mjs`로 재현합니다. Dart 문법이나 외부 패키지 API 호환성을 보증하지 않습니다. 실행 가능한 환경에서 bootstrap/check 스크립트와 Android/iOS 빌드로 확인해야 합니다.

`shared/test`에는 기존 업무 모델, 인증, 개발 진입 외에 HTTP 인증 헤더·갱신·오류 노출 제한·안전한 주소 처리, 알림 payload 검증·로그인 전 클릭 보류·중복/역할 필터링·만료 알림 처리 테스트가 있습니다. `flutter test --no-pub`의 26개 및 `flutter test --no-pub --dart-define=ALLCHECK_DEV_ACCESS=true test/development_access_test.dart`의 2개가 통과했습니다. 이 결과는 실제 Firebase/백엔드/에뮬레이터 검증을 대신하지 않습니다.

실기기 검증에는 관리자/기사별 Firebase 앱 등록, APNs 설정, 카카오 앱 키, 실제 서버 API와 최초 관리자 계정이 필요합니다. 푸시는 foreground/background/terminated 각각에서 확인하고 권한 거절/토큰 갱신/로그아웃/재로그인/만료 배차/동시 수락/네트워크 실패도 확인해야 합니다. 백그라운드 위치는 OS 권한·배터리 정책 및 앱 프로세스 생명주기에 따라 달라지므로 별도로 측정해야 합니다.

이전에 생성한 WebView APK는 현재 Flutter 소스의 결과물이 아닙니다.
