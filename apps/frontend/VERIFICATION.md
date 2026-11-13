# 검증 기록

2026-11-13 기준입니다.

| 항목 | 상태 |
|---|---|
| 두 앱 진입점·공유 패키지·Dart part·네이티브 채널·iOS/Web 구조 | Node 구조 점검 통과 |
| Android manifest·iOS plist/entitlements | XML 파싱 확인 |
| Git 공백 검사 | 통과 |
| Dart 실행·의존성 설치 | 애플리케이션 제어(Device Guard) 정책에 의해 차단 |
| Flutter 분석·테스트·APK/iOS/Web 빌드 | 실행하지 못함 |
| 실제 서버 인증·배차·FCM/APNs·위치·파일 전송 | 서버 계약/프로젝트 설정 및 실기기 검증 필요 |
| 에뮬레이터 최신 Flutter 앱 실행 | 새 APK 빌드 불가로 미완료 |

구조 검사는 `node apps/frontend/scripts/check-structure.mjs`로 재현합니다. Dart 문법이나 외부 패키지 API 호환성을 보증하지 않습니다. 실행 가능한 환경에서 bootstrap/check 스크립트와 Android/iOS 빌드로 확인해야 합니다.

`shared/test`에는 기존 업무 모델, 인증, 개발 진입 외에 HTTP 인증 헤더·갱신·오류 노출 제한·안전한 주소 처리, 알림 payload 검증·로그인 전 클릭 보류·중복/역할 필터링·만료 알림 처리 테스트가 있습니다. 현재 PC에서는 이 Dart 테스트를 실행하지 못했습니다.

실기기 검증에는 관리자/기사별 Firebase 앱 등록, APNs 설정, 카카오 앱 키, 실제 서버 API와 최초 관리자 계정이 필요합니다. 푸시는 foreground/background/terminated 각각에서 확인하고 권한 거절/토큰 갱신/로그아웃/재로그인/만료 배차/동시 수락/네트워크 실패도 확인해야 합니다. 백그라운드 위치는 OS 권한·배터리 정책 및 앱 프로세스 생명주기에 따라 달라지므로 별도로 측정해야 합니다.

이전에 생성한 WebView APK는 현재 Flutter 소스의 결과물이 아닙니다.