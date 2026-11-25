# 관리자 앱, 기사앱

브랜치: `feat/ACK-22-admin-app-demo`

앱 소스와 실행 방법은 [README](README.md), 서버 연결 기준은 [API-CONTRACT](API-CONTRACT.md), 실행 검증 범위는 [VERIFICATION](VERIFICATION.md)을 기준으로 확인합니다.

현재 결정에 따라 관리자 인증은 사전 발급한 아이디·비밀번호만 사용하며 MFA는 구현하지 않습니다. FR-01/UC-01/SEC-AUTH-02와 OpenAPI 초안도 이 정책을 따릅니다. 프론트에 관리자 비밀번호를 고정하거나 공개 관리자 회원가입을 제공하지 않습니다.

백엔드 담당자는 `FrontendBackend`의 경로/DTO를 실제 서버와 맞추고, 최초 관리자 계정 생성, 서버 인증·인가, 토큰 갱신/폐기, 후보 선정, 배차 경쟁 처리, 파일 저장과 푸시 발송을 연결해야 합니다. Firebase 앱 설정 및 APNs 인증도 필요합니다.
