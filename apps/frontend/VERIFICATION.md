# 검증 기록

2026-11-13 기준입니다. 카카오 로그인 및 UI 정리 변경의 상세 범위는 [ACK-22 인계 문서](ACK-22-HANDOFF.md)를 참고하세요.

| 항목 | 상태 |
|---|---|
| 두 앱 진입점, 공유 패키지 참조, Android 채널/ID, iOS·웹 템플릿, 244개 프레임 목록 | Node 구조 점검 통과 |
| Dart 의존성 설치 | Device Guard 차단으로 미완료 |
| Dart/Flutter 정적 분석 | 실행하지 못함 |
| 공유 모델 및 위젯 테스트 | 소스 작성, 실행하지 못함 |
| Android APK, iOS, 웹 빌드 | 실행하지 못함 |
| 에뮬레이터·실기기 화면/플러그인 검증 | 미완료 |
| Figma 전체 화면의 시각적 일치 검증 | 미완료 |
| 실서버·인증·AI·푸시 연결 | 미연결 |

구조 점검은 다음 명령으로 재현합니다.

```powershell
node apps/frontend/scripts/check-structure.mjs
```

이 검사는 Dart 문법이나 패키지 API 호환성, 네이티브 빌드 성공을 보증하지 않습니다. 보안 정책이 허용하는 환경에서 README의 bootstrap/check/build 명령을 실행하고 발견되는 오류를 수정해야 합니다. iOS는 별도로 macOS에서 빌드/서명 및 플러그인 검증이 필요합니다.

기존 JavaScript 프론트 테스트 결과는 Flutter 코드 검증을 대신하지 않습니다. 기존 APK를 Flutter 빌드 결과로 배포하면 안 됩니다.
