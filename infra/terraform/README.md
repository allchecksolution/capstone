# ALLCHECK 클라우드 개발 뼈대

현재는 Backend와 PostgreSQL을 개발 PC에서 실행하고, S3·FCM만 외부 서비스로 사용하는 단계다.
이 디렉터리의 코드를 작성하는 것만으로 자원이 생성되거나 인증되는 것은 아니다.

```text
infra/terraform/
├─ s3/    새 비공개 버킷과 녹음 접근 정책
└─ fcm/   콘솔에서 만든 Firebase 프로젝트의 API·발송 계정·권한
```

각 폴더는 별도 Terraform 루트이며 별도로 init/plan/apply한다. 상위 폴더에서 명령을 실행해도
하위 폴더들을 자동으로 함께 적용하지 않는다. 현재 state backend는 각 루트의 로컬 기본값이다.

## 나중에 실제 연결할 순서

1. [S3 입력 예시](s3/terraform.tfvars.example)에 계정·리전·새 버킷 이름을 넣고 [S3 가이드](s3/README.md)를 따른다.
2. S3 정책을 로컬 Backend가 사용하는 AWS 주체에 연결하고 런타임 인증을 준비한다.
   Terraform 프로필과 Backend 프로필은 별도 설정이다. 기존 버킷이 없으므로 import는 필요하지 않다.
3. Firebase 콘솔에서 개발 프로젝트를 최초 생성하고 앱을 등록한다.
4. [FCM 입력 예시](fcm/terraform.tfvars.example)에 프로젝트 ID·개발자를 넣고 [FCM 가이드](fcm/README.md)를 따른다.
5. 로컬 ADC를 준비하고 [Backend 연결 가이드](../../apps/backend/docs/local-cloud-setup.md)의 환경변수를 설정한다.

Terraform의 관리자 인증과 Backend의 실행 인증을 구분한다. 실제 식별자는 개인 tfvars에,
자격증명은 외부 인증 저장소에 둔다. 템플릿의 예시 값으로 apply하지 않는다.

## 명령의 의미

| 명령 | 역할 |
|---|---|
| init | provider 설치와 초기화. 클라우드 리소스를 생성하지 않음 |
| fmt / validate | 코드 형식과 provider 스키마 검증 |
| test | 현재 루트의 mock 테스트. 실제 계정에 접속하지 않음 |
| plan | 실제 대상과 비교해 생성·변경·삭제 계획 확인 |
| apply | 검토한 계획대로 실제 자원·권한 변경 |

두 루트 모두 state·plan·개인 tfvars를 Git에서 제외한다. provider lock 파일은 포함한다.
공동 적용 전에 원격 state와 동시 변경 잠금을 정한다. state 파일을 잃어버리지 않는다.
실제 업로드·기기 알림 검증은 별도다. 서버·RDS·로드 밸런서·운영 환경은 추후 배포 단계에서 구성한다.
