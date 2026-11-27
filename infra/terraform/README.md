# ALLCHECK Terraform

로컬 백엔드의 S3·FCM 연동과 AWS 스테이징 인프라를 구성한다.

```text
infra/terraform/
├─ application/  백엔드·AI·PostgreSQL·NPM을 실행할 스테이징 EC2와 배포 권한
├─ s3/    새 비공개 버킷과 녹음 접근 정책
└─ fcm/   콘솔에서 만든 Firebase 프로젝트의 API·발송 계정·권한
```

각 폴더는 별도 Terraform 루트이며 별도로 init/plan/apply한다. 상위 폴더에서 명령을 실행해도
하위 폴더들을 자동으로 함께 적용하지 않는다. 현재 state backend는 각 루트의 로컬 기본값이다.

## 로컬 개발 환경 연결 순서

1. [S3 입력 예시](s3/terraform.tfvars.example)에 계정·리전·새 버킷 이름을 넣고 [S3 가이드](s3/README.md)를 따른다.
2. S3 정책을 로컬 Backend가 사용하는 AWS 주체에 연결하고 런타임 인증을 준비한다.
   Terraform 프로필과 Backend 프로필은 별도 설정이다. 기존 버킷이 없으므로 import는 필요하지 않다.
3. Firebase 콘솔에서 개발 프로젝트를 최초 생성하고 앱을 등록한다.
4. [FCM 입력 예시](fcm/terraform.tfvars.example)에 프로젝트 ID·개발자를 넣고 [FCM 가이드](fcm/README.md)를 따른다.
5. 로컬 ADC를 준비하고 [Backend 연결 가이드](../../apps/backend/docs/local-cloud-setup.md)의 환경변수를 설정한다.

Terraform의 관리자 인증과 Backend의 실행 인증을 구분한다. 실제 식별자는 개인 tfvars에,
자격증명은 외부 인증 저장소에 둔다. 템플릿의 예시 값으로 apply하지 않는다.

## AWS 스테이징 연결

[애플리케이션 Terraform](application/terraform.tfvars.example)으로 EC2와 배포 역할을 준비한다.
스테이징에서 S3를 연결할 때는 애플리케이션의 `ec2_role_name` 출력을 S3 입력에 전달한다.
스테이징 FCM은 [서버용 발송 인증](fcm/README.md#스테이징-백엔드-발송-인증)을 준비한 뒤 활성화한다.

## 명령의 의미

| 명령 | 역할 |
|---|---|
| init | provider 설치와 초기화. 클라우드 리소스를 생성하지 않음 |
| fmt / validate | 코드 형식과 provider 스키마 검증 |
| test | 현재 루트의 mock 테스트. 실제 계정에 접속하지 않음 |
| plan | 실제 대상과 비교해 생성·변경·삭제 계획 확인 |
| apply | 검토한 계획대로 실제 자원·권한 변경 |

각 루트의 state·plan·개인 tfvars를 Git에서 제외한다. provider lock 파일은 포함한다.
공동 적용 전에 원격 state와 동시 변경 잠금을 정한다. state 파일을 잃어버리지 않는다.
