# AI API v1.2 초안 검증

검증 대상은 [AI 명세](ai-api-v1.2.yaml), [웹훅 명세](backend-callback-v1.2.yaml), [SQL 초안](../../backend/src/main/resources/db/migration/V1__create_ai_job_tables.sql.draft)이다.

검증일: 2026-11-06. Python 3.13.9 / Windows에서 정적 검증했다. DB 연결·SQL 적용·FastAPI 내부 API 구현 테스트는 포함하지 않는다.

## 결과

- OpenAPI 3.1.1 문서 2개: 구조·내부 참조 검증 통과
- PR #7 HEAD `341a01597af2d697cd53906fb27ed8c4b7e44072`와 5개 공유 스키마를 각 문서에서 구조적으로 동일함을 확인
- 스키마 예시 12개 검증, ID·멱등성 키·한글 문자열 길이·Enum·날짜·추가 필드·required/null·작업 상태별 결과 조합의 정상/실패 사례 검증
- 총 111개 검사 통과
- PostgreSQL 17 계열 pglast 파서로 SQL 6개 문장 파싱 성공: 테이블 2개·인덱스 4개, 요청한 컬럼 목록·초안 확장자 확인
- SQL에 CREATE TABLE/INDEX 외 DML·기존 객체 변경 명령이 없음을 AST로 확인
- Markdown 로컬 링크 검사 포함
- 최신 합의의 Backend DB/Flyway/정식 데이터 관리와 FastAPI AI 전용 접근·실행 상태 책임을 YAML·설계 문서·SQL 주석에 반영한 뒤 재검증했다. 인증·비동기 작업자/재시작 복구·Backend 저장 확인 및 보관/삭제는 TODO로 유지했다.

검증 도구: openapi-spec-validator 0.9.0, jsonschema 4.26.0, pglast 7.22. 도구는 Git 제외된 `.tools`에만 설치했으며 앱 의존성과 uv.lock은 변경하지 않았다.

## 재실행

저장소 루트에서 실행한다. 기준 PR 커밋의 git 객체가 로컬에 있어야 한다. 현재 검증에는 기존 로컬 객체를 사용했으며 fetch하지 않았다.

```powershell
.\apps\ai\.tools\Scripts\python.exe -m pip install openapi-spec-validator==0.9.0 jsonschema==4.26.0 pglast==7.22
.\apps\ai\.tools\Scripts\python.exe apps/ai/docs/validate_contracts_v1_2.py
git diff --check
```

검증 스크립트는 파일·DB를 변경하지 않는다. 신규 파일도 포함한 최종 공백 검사를 별도로 실행했다.

## 검증 한계

JSON Schema가 두 필드의 의미적 일치(경로와 본문의 jobId, timeZone과 referenceTime, missingFields와 추출 결과)를 자동 보장하지는 않는다. 구현 시 별도 교차 검증이 필요하다. 실제 Swagger UI 렌더링·Mock HTTP 호출·멀티파트 업로드·비동기 처리·인증·웹훅 재시도는 미검증이다.

SQL 파싱은 실제 PostgreSQL 17에서의 DDL 적용·제약 동작·업그레이드·동시성·실행계획 검증을 대신하지 않는다. 저장소 규칙상 병합 전에 승인된 테스트 DB에서 적용/업그레이드 검증이 필요하다. 공유 DB의 flyway_schema_history와 팀원의 미공개 migration은 확인하지 않았다.
