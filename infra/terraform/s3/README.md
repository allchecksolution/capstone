# 비공개 S3 기본 구성

공통 S3 인프라를 생성하고 접수 녹음 권한을 연결하는 Terraform 루트입니다.
S3 통신 코드는 Backend의 `global.config.S3Config`, `global.storage.S3StorageClient`를 사용합니다.
각 도메인은 자신의 버킷·객체 키·파일 정책을 결정합니다. 현재 접수 녹음은 `recordings/` 경로를 사용합니다.

## 구성

- 비공개 버킷, 네 가지 Public Access Block, ACL 비활성화(`BucketOwnerEnforced`), 기본 SSE-S3 암호화.
- HTTPS 강제와 `recordings/*`의 조건부 쓰기 강제. 다른 도메인의 경로에 녹음 불변 정책을 적용하지 않습니다.
- 녹음 경로의 GetObject/PutObject 및 누락 객체 확인용 ListBucket IAM 정책. 삭제·ACL·버킷 관리 권한은 부여하지 않습니다.
- 명시한 기존 Backend 실행 역할에만 권한 정책을 연결합니다. 역할이 미정이면 정책만 생성합니다.
- 브라우저 origin을 명시했을 때만 CORS를 생성합니다. 네이티브 앱은 CORS가 필요하지 않습니다.
- `prevent_destroy=true`, `force_destroy=false`. 보존 기간이 미정이므로 자동 삭제·아카이브·버전 정책은 추가하지 않습니다.

객체가 없을 때 S3 HEAD는 ListBucket 권한 유무에 따라 404 또는 403을 반환합니다.
Backend가 미완료 업로드와 저장소 권한 장애를 구분하도록 ListBucket을 허용합니다.
이 권한은 버킷 내 객체 이름을 조회할 수 있으므로, 실행 역할의 다른 정책까지 포함해 접근 범위를 확인합니다.
사진·프로필 경로의 객체 읽기/쓰기는 현재 녹음 정책에 포함하지 않습니다.

## 현재 개발 단계

S3는 아직 생성하지 않았고 Backend는 개발 PC에서 실행한다. 이 루트는 새 버킷 생성용이다.
현재 단계에서 import는 필요하지 않다. Firebase/FCM 설정은 별도 루트로 관리한다.

- 서버 실행 역할이 아직 없으면 backend_role_name을 지정하지 않는다.
- 이 경우 IAM 정책만 생성되며, 로컬 Backend의 AWS 인증 주체에 정책을 연결하는 작업은 별도로 필요하다.
- aws_profile은 Terraform의 인증 설정이다. Backend의 AWS_PROFILE은 별도로 설정한다.
- AWS_ACCESS_KEY_ID 등의 비밀값은 tfvars에 넣지 않는다.
- 구성 작성이나 init/validate/test는 실제 S3 배포가 아니다. 실제 계정 정보를 정한 뒤 plan을 검토하고 apply한다.

로컬 Backend 환경변수와 인증 절차는 [로컬 클라우드 연동 가이드](../../../apps/backend/docs/local-cloud-setup.md)를 참고한다.

## 적용 정보

Terraform 1.9 이상을 사용합니다. AWS provider 버전은 `.terraform.lock.hcl`로 고정합니다.
계정·환경·리전·버킷 이름은 필수 입력이며 기본 계정을 임의로 선택하지 않습니다.

1. `terraform.tfvars.example`을 로컬 `.tfvars`로 복사하고 실제 대상 계정과 버킷 이름을 입력합니다.
2. 로컬 AWS 프로필을 사용하면 `aws_profile`을 지정합니다. 배포 환경에서는 실행 역할/자격증명 체인을 사용합니다.
3. 기존 Backend 실행 역할 이름을 알면 `backend_role_name`을 지정합니다. 새 역할의 신뢰 정책은 배포 방식 확정 후 구성합니다.

저장소 루트에서 실행합니다.

```sh
terraform -chdir=infra/terraform/s3 init
terraform -chdir=infra/terraform/s3 fmt -check -recursive
terraform -chdir=infra/terraform/s3 validate
terraform -chdir=infra/terraform/s3 test
terraform -chdir=infra/terraform/s3 plan -var-file=dev.tfvars -out=dev.tfplan
terraform -chdir=infra/terraform/s3 apply dev.tfplan
```

예제 계정 `000000000000`은 실제 계정으로 바꿔야 합니다. Provider의 `allowed_account_ids`가 다른 계정 적용을 거부합니다.
기존 버킷을 사용한다면 먼저 소유권·정책과 기존 데이터를 확인하고 해당 리소스를 Terraform에 import합니다.
자동으로 기존 버킷을 채택하거나 초기화하지 않습니다.

로컬 state를 사용하며 state·계획·환경별 변수는 Git에서 제외합니다. 팀이 공동 적용하기 전에는
접근 권한과 잠금이 있는 원격 state 저장소를 별도로 정해야 합니다. Terraform 자격증명은 tfvars에 넣지 않습니다.

## Backend 연결

```sh
terraform -chdir=infra/terraform/s3 output -json backend_environment
terraform -chdir=infra/terraform/s3 output recordings_policy_arn
```

출력의 `S3_ENABLED`, `S3_REGION`, `RECEPTION_RECORDING_BUCKET`을 배포 설정에 반영하고
`S3_REQUEST_TIMEOUT`도 지정합니다. AWS SDK 자격증명은 실행 역할 또는 외부 런타임 설정에서 제공하며 출력에 담지 않습니다.
AI 연동·작업자 설정은 [Backend API 컨벤션](../../../apps/backend/docs/api-conventions.md#reception-integration)을 따릅니다.

Backend는 HEAD의 크기/MIME/SHA256과 조건부 범위 GET의 WAVE 헤더를 검증합니다. 업로드 URL은 DB에 저장하지 않습니다.
단위 테스트는 실제 AWS 리소스 없이 모의 provider로 공개 차단·암호화·권한·CORS·환경 출력 정책을 확인합니다.
실제 계정의 IAM 권한, 버킷 생성 및 앱 업로드 검증은 지정된 대상에 적용한 뒤 확인해야 합니다.

참고: [S3 조건부 쓰기 강제](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes-enforce.html),
[HEAD 권한과 오류](https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html),
[Terraform provider mocking](https://developer.hashicorp.com/terraform/language/tests/mocking).
