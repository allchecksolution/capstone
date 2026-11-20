# 개발용 FCM 설정

Firebase 콘솔에서 최초 프로젝트를 만든 뒤 FCM API, 발송 전용 서비스 계정과 권한을 관리하는 Terraform 루트다.
Firebase 프로젝트·앱·서버 자체를 생성하지 않는다. 현재는 실제 프로젝트 ID가 없는 설정 뼈대다.

## 구성

- Google provider는 외부 ADC/환경 인증을 사용한다. 코드와 tfvars에 자격증명을 넣지 않는다.
- FCM HTTP v1, IAM, IAM Credentials와 Resource Manager API를 활성화한다.
- 발송용 서비스 계정과 `cloudmessaging.messages.create`만 포함한 프로젝트 사용자 정의 역할을 만든다.
- 프로젝트의 기존 IAM 정책을 교체하지 않고 개별 member만 추가한다.
- 명시한 개발자에게만 이 서비스 계정의 Token Creator를 부여한다. 기본 목록은 비어 있다.
- 개인키를 생성하지 않고, API는 이 루트를 제거하더라도 자동 비활성화하지 않는다.

## 1. 콘솔에서 최초 프로젝트 준비

1. Firebase 콘솔에서 개발용 프로젝트를 만든다. Analytics 등 부가 기능은 실제 필요에 따라 선택한다.
2. 프로젝트 설정에서 **프로젝트 ID**를 확인한다. 표시 이름·숫자 프로젝트 번호와 다르다.
3. 실제 테스트할 관리자/기사 앱을 같은 프로젝트에 등록하고 플랫폼별 설정을 앱에 적용한다.
   iOS 푸시는 APNs 설정도 별도로 필요하다. 앱 등록·APNs 자격증명은 이 Terraform 범위가 아니다.
4. Terraform 실행 계정에 API 활성화, 서비스 계정 생성, 사용자 정의 역할 생성과 해당 IAM 변경 권한을 준비한다.
   Service Usage API가 꺼져 있다면 콘솔/관리자 계정으로 먼저 활성화한다. 자기 권한을 Terraform이 자동 부여하지 않는다.

조직 정책·계정별 API/결제 요구는 프로젝트에서 확인한다. 이 코드가 결제 계정이나 조직 정책을 변경하지 않는다.

## 2. Terraform 관리자 인증과 적용 준비

Google Cloud CLI 설치 후 관리자 계정의 ADC를 준비한다. 다음 명령은 뼈대 작성 단계에서 실행하지 않는다.

```powershell
gcloud auth login
gcloud auth application-default login
```

환경변수 GOOGLE_APPLICATION_CREDENTIALS가 있으면 로컬 ADC보다 우선하므로 의도한 인증 경로인지 확인한다.
dev.tfvars에 실제 프로젝트 ID와 허용할 개발자를 입력한다. 예시 파일의 프로젝트 ID는 적용 대상이 아니다.

저장소 루트에서:

```powershell
terraform -chdir=infra/terraform/fcm init
terraform -chdir=infra/terraform/fcm fmt -check -recursive
terraform -chdir=infra/terraform/fcm validate
terraform -chdir=infra/terraform/fcm test
terraform -chdir=infra/terraform/fcm plan -var-file=dev.tfvars -out=dev.tfplan
# 검토 후 실제 계정·권한을 만드는 별도 단계:
terraform -chdir=infra/terraform/fcm apply dev.tfplan
```

이 루트는 로컬 state를 사용한다. state·plan·개인 변수는 Git에서 제외하며 공유 적용 전에 원격 state·잠금을 정한다.
init 후 생성된 .terraform.lock.hcl은 검증하고 Git에 포함해 팀의 provider 버전을 고정한다.
동일 리소스가 이미 별도 관리되고 있다면 중복 생성하지 말고 관리 주체/import 여부를 확인한다.

## 3. 로컬 백엔드 발송 인증

적용 후 `sender_service_account_email` 출력을 확인한다. developer_members에 등록된 개발자는
개인키 없이 그 서비스 계정으로 ADC를 준비할 수 있다.

```powershell
gcloud auth application-default login --impersonate-service-account=<sender_service_account_email>
```

이 명령은 PC의 로컬 ADC를 변경한다. 관리자 Terraform 인증과 백엔드 발송 인증의 용도가 다르다.
다시 Terraform으로 IAM/API를 변경할 때는 관리자 ADC를 복구하거나 별도 인증 환경을 사용한다.
서비스 계정 발송 권한으로 인프라를 관리하려고 하면 권한 오류가 난다.
quota-project 관련 오류가 발생하면 지정된 quota 프로젝트와 해당 사용자 인증의
serviceusage.services.use 권한을 확인한다. 발송 계정에 Owner/Editor를 부여해 해결하지 않는다.

백엔드는 `FCM_ENABLED`, `FCM_PROJECT_ID`, connect/request timeout과 ADC를 필요로 한다.
[로컬 클라우드 가이드](../../../apps/backend/docs/local-cloud-setup.md)의 예시를 따른다.
FCM_ENABLED만 켜도 업무 이벤트나 기기 토큰이 생성되는 것은 아니다.

## 검증 범위와 향후 배포

mock provider 테스트는 권한 범위·명시적 개발자 등록·API 유지 설정을 검사하며 실제 Google 호출을 하지 않는다.
실제 발송은 프로젝트·앱 등록·기기 토큰·서버 인증이 준비된 뒤 확인한다.
나중에 AWS 배포 방식이 정해지면 WIF 등 서버 인증을 별도로 구성하고 개발자 ADC를 배포 이미지에 넣지 않는다.

참고: [FCM 발송 권한](https://docs.cloud.google.com/iam/docs/roles-permissions/firebasecloudmessaging),
[로컬 ADC](https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment),
[Google IAM member](https://registry.terraform.io/providers/hashicorp/google/latest/docs/resources/google_project_iam).
