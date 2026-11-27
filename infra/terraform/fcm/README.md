# 개발용 FCM 설정

Firebase 콘솔에서 최초 프로젝트를 만든 뒤 FCM API, 발송 전용 서비스 계정과 권한을 관리하는 Terraform 루트다.
Firebase 프로젝트·앱·서버 자체를 생성하지 않는다. 실제 프로젝트는 Git에서 제외된 `dev.tfvars`로 지정한다.

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
terraform -chdir=infra/terraform/fcm plan '-var-file=dev.tfvars' '-out=dev.tfplan'
# 검토 후 실제 계정·권한을 만드는 별도 단계:
terraform -chdir=infra/terraform/fcm apply dev.tfplan
```

PowerShell에서는 점이 포함된 `-var-file=...`와 `-out=...` 인수 전체를 위처럼 따옴표로 감싼다.
특히 Windows PowerShell 5.1에서는 따옴표가 없으면 파일명이 여러 인수로 나뉠 수 있다.

| 명령 | 의미 |
|---|---|
| `-chdir=infra/terraform/fcm` | 저장소 루트 대신 해당 폴더의 Terraform 설정을 사용한다. 변수·plan 파일의 상대 경로도 이 폴더 기준이다. |
| `init` | Google provider 등 필요한 구성요소와 state 저장 구성을 초기화한다. |
| `validate` | Terraform 구성의 문법과 참조·타입을 검증한다. 실제 발송 권한이나 기기 수신은 검증하지 않는다. |
| `test` | 이 루트에서는 mock Google provider로 설정을 검사하며 실제 클라우드 자원을 만들지 않는다. |
| `plan '-var-file=dev.tfvars' '-out=dev.tfplan'` | 로컬 변수 파일로 변경 계획을 계산하고 `dev.tfplan`에 저장한다. Google 조회가 필요할 수 있지만 계획 자체는 자원을 생성하거나 IAM 권한을 변경하지 않는다. |
| `apply dev.tfplan` | 저장한 계획대로 API 활성화, 발송 서비스 계정 및 IAM 권한을 실제 적용한다. 저장된 계획을 적용할 때는 추가 승인 질문이 없으므로 먼저 plan을 검토한다. |

이 루트는 로컬 state를 사용한다. state·plan·개인 변수는 Git에서 제외하며 공유 적용 전에 원격 state·잠금을 정한다.
init 후 생성된 .terraform.lock.hcl은 검증하고 Git에 포함해 팀의 provider 버전을 고정한다.
동일 리소스가 이미 별도 관리되고 있다면 중복 생성하지 말고 관리 주체/import 여부를 확인한다.

## 3. 로컬 백엔드 발송 인증

적용 후 `sender_service_account_email` 출력을 확인한다. developer_members에 등록된 개발자는
개인키 없이 그 서비스 계정으로 ADC를 준비할 수 있다.

```powershell
$senderEmail = terraform -chdir=infra/terraform/fcm output -raw sender_service_account_email
gcloud auth application-default login "--impersonate-service-account=$senderEmail"
```

첫 줄은 생성한 발송 서비스 계정의 이메일을 Terraform state에서 읽어 PowerShell 변수에 저장한다.
두 번째 줄은 해당 계정의 권한으로 토큰을 얻을 수 있도록 로컬 ADC를 준비한다.
서비스 계정 개인키를 생성하거나 다운로드하는 명령이 아니다.

이 명령은 PC의 로컬 ADC를 변경한다. 관리자 Terraform 인증과 백엔드 발송 인증의 용도가 다르다.
다시 Terraform으로 IAM/API를 변경할 때는 관리자 ADC를 복구하거나 별도 인증 환경을 사용한다.
서비스 계정 발송 권한으로 인프라를 관리하려고 하면 권한 오류가 난다.
quota-project 관련 오류가 발생하면 지정된 quota 프로젝트와 해당 사용자 인증의
serviceusage.services.use 권한을 확인한다. 발송 계정에 Owner/Editor를 부여해 해결하지 않는다.

백엔드는 `FCM_ENABLED`, `FCM_PROJECT_ID`, connect/request timeout과 ADC를 필요로 한다.
[로컬 클라우드 가이드](../../../apps/backend/docs/local-cloud-setup.md)의 예시를 따른다.
FCM_ENABLED만 켜도 업무 이벤트나 기기 토큰이 생성되는 것은 아니다.

### 노트북에서 테스트하기

노트북에서는 Google 사용자 계정의 서비스 계정 impersonation으로 테스트한다.
`staging-google-adc.json`은 EC2 메타데이터를 사용하므로 노트북의 `GOOGLE_APPLICATION_CREDENTIALS`에 지정하지 않는다.
노트북 테스트는 AWS 역할을 통한 WIF 인증을 검증하지 않는다. 그것은 배포 후 EC2 컨테이너에서 확인한다.

1. 저장소 루트에서 `terraform -chdir=infra/terraform/fcm test`를 실행한다. mock provider 테스트 6개가
   통과해야 한다. 실제 Google/AWS 호출, 푸시 발송이나 인프라 변경은 없다.
2. 로컬 ADC가 아직 없다면 앞 절의 `gcloud auth application-default login --impersonate-service-account=...`로
   준비한다. 이미 발송 계정의 impersonation ADC가 있으면 다시 로그인할 필요가 없다.
3. 현재 PowerShell에서 아래 명령으로 로컬 ADC의 단기 토큰 발급을 확인한다.

```powershell
# 이 노트북의 설치 경로. gcloud가 PATH에 있는 다른 PC에서는 그 명령으로 대체한다.
$gcloudCmd = 'D:\ProgramFiles\google-cloud-sdk\bin\gcloud.cmd'
# 이 PowerShell 프로세스에서만 파일 지정 해제. ADC 파일은 삭제하지 않는다.
Remove-Item Env:GOOGLE_APPLICATION_CREDENTIALS -ErrorAction SilentlyContinue
$localFcmAccessToken = & $gcloudCmd auth application-default print-access-token
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($localFcmAccessToken)) {
  throw '로컬 발송 계정 인증을 확인하세요.'
}
Write-Output '단기 토큰 발급 성공'
```

토큰을 화면에 출력하거나 공유하지 않는다. 토큰 발급 성공은 FCM 발송·기기 수신 성공과는 구분한다.
추가로 FCM에 실제 요청을 보내려면 같은 Firebase 프로젝트의 Android 앱을
`--dart-define=ALLCHECK_PUSH_ENABLED=true`로 실행하고 알림 권한을 허용한다.
이 PC에는 두 앱의 `google-services.json`이 있지만 Git에서는 제외되므로 다른 PC에는 별도 준비한다.
현재 앱에 토큰 복사 UI는 없으므로 Flutter 디버거에서 `FirebaseAppPush.token()` 반환값 등으로
해당 설치의 FCM 등록 토큰을 확인한다. 토큰을 얻은 다음 앱을 백그라운드로 전환한다.

다음 요청은 처음에는 `validate_only = $true`여서 발송하지 않고 FCM 요청만 검증한다.
성공 후 직접 `$false`로 바꿔 다시 실행하면 입력한 기기 한 대에 테스트 알림을 보낸다.

```powershell
$testDeviceToken = Read-Host '테스트 기기의 FCM 등록 토큰'
try {
  $testMessage = @{
    validate_only = $true
    message = @{
      token = $testDeviceToken
      notification = @{ title = 'ALLCHECK 테스트'; body = '노트북에서 보낸 FCM 테스트입니다.' }
    }
  } | ConvertTo-Json -Depth 4
  $fcmReply = Invoke-RestMethod -Method Post `
    -Uri 'https://fcm.googleapis.com/v1/projects/allcheck-dev-7c794/messages:send' `
    -Headers @{ Authorization = "Bearer $localFcmAccessToken" } `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($testMessage))
  Write-Output 'FCM 요청 성공 (validate_only 값을 확인하세요)'
} finally {
  Remove-Variable localFcmAccessToken,testDeviceToken,testMessage,fcmReply -ErrorAction SilentlyContinue
}
```

실제 발송으로 바꿔 다시 실행하기 전에는 위 인증 명령으로 토큰 변수를 다시 준비한다.
이 테스트는 백엔드·DB를 거치지 않고 동일한 발송 계정에서 FCM으로 직접 전송한다.
notification만 포함하므로 백그라운드 OS 알림을 확인하며, 앱 내부 배너나 클릭 후 화면 이동은 검증하지 않는다.
현재 프론트의 `devices/register`와 백엔드의 `PUT /api/v1/push-devices/{installationId}`는 매핑이 필요하고,
기사 가입·접수·배차 알림의 data 필드도 프론트 수신 형식과 다르다. 따라서 앱 업무 이벤트부터의 전체 테스트는
이 연결을 맞춘 후 진행한다. 단순 `bootRun` 성공만으로 실제 발송까지 확인됐다고 보지 않는다.

참고: [FCM validate_only](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send),
[Flutter 기기 등록 토큰](https://firebase.google.com/docs/cloud-messaging/flutter/get-started).

## 스테이징 백엔드 발송 인증

AWS 스테이징에서 발송하려면 EC2의 `/etc/allcheck/backend/google-adc.json`에 서버용 ADC를 준비한다.
Compose가 이 파일을 컨테이너의 `/run/allcheck/google-adc.json`에 읽기 전용으로 마운트하며 백엔드 UID 10001이 읽을 수 있어야 한다.
Secrets Manager의 `allcheck/staging/backend/runtime`에 `GOOGLE_APPLICATION_CREDENTIALS=/run/allcheck/google-adc.json`,
`FCM_PROJECT_ID`, `FCM_CONNECT_TIMEOUT`, `FCM_REQUEST_TIMEOUT`을 설정한 뒤 `FCM_ENABLED`를 활성화한다.
첫 스테이징의 기동 검증은 `FCM_ENABLED` 기본값인 `false`로 진행할 수 있다.

### AWS EC2 Workload Identity Federation

기존 state와 `dev.tfvars`에서 선택적으로 `staging_wif`를 추가한다. 기존 발송 계정·FCM 발송 역할·개발자의
Token Creator 권한은 유지하고 STS API, 전용 WIF 풀, AWS 공급자와 서비스 계정 IAM member만 추가한다.
`staging_wif`를 생략하면 WIF를 만들지 않는다. 이미 적용한 값을 제거하면 WIF 삭제 계획이 나오므로 유지한다.

```hcl
staging_wif = {
  project_number = "414145711645"
  aws_account_id = "303357410104"
  aws_role_name  = "allcheck-staging-ec2"
  # pool_id     = "allcheck-staging-fcm"
  # provider_id = "aws-ec2"
}
```

프로젝트 번호는 실제 프로젝트 조회 결과와 검증한다. 공급자는 AWS 계정과 정확한 assumed-role ARN을
함께 검사하며, IAM member도 해당 역할의 `attribute.aws_role` principalSet으로 제한한다.
이 권한은 같은 역할을 사용하는 워크로드 전체에 적용되며 특정 EC2 인스턴스 한 대로 제한하지 않는다.
다른 풀 공급자를 추가할 때도 이 속성의 신뢰 범위를 유지해야 한다.

관리자 인증으로 다음을 실행한다. 기존 발송 계정 impersonation ADC는 인프라 관리용이 아니다.

```powershell
terraform -chdir=infra/terraform/fcm fmt -check -recursive
terraform -chdir=infra/terraform/fcm validate
terraform -chdir=infra/terraform/fcm test
terraform -chdir=infra/terraform/fcm plan '-var-file=dev.tfvars' '-out=staging-wif.tfplan'
# 기존 리소스 변경/교체/삭제 없이 위 4개 리소스만 추가되는지 확인 후:
terraform -chdir=infra/terraform/fcm apply staging-wif.tfplan

$providerName = terraform -chdir=infra/terraform/fcm output -raw staging_wif_provider_name
$senderEmail = terraform -chdir=infra/terraform/fcm output -raw sender_service_account_email
# 출력 디렉터리는 먼저 준비한다. 로컬 개발자 ADC를 덮어쓰지 않는다.
gcloud iam workload-identity-pools create-cred-config $providerName "--service-account=$senderEmail" --aws --enable-imdsv2 '--output-file=infra/terraform/fcm/staging-google-adc.json'
```

생성 파일은 `external_account` 구성으로, 개인키나 실제 액세스 토큰을 담지 않는다.
이 PC에서는 EC2 역할을 증명할 수 없으므로 파일 생성만으로 서버 인증 성공을 검증하지 못한다.
EC2 파일 배치, Secrets Manager 수정과 재배포 후 실제 컨테이너에서 인증·FCM 수신을 검증한다.

EC2에는 대상 역할의 instance profile이 실제 연결되어야 하며 IMDSv2가 활성화되어야 한다.
Docker bridge 네트워크에서는 메타데이터 응답 hop limit `2`가 필요할 수 있다.
현재 application 루트는 `enable_media_access`가 true일 때만 `2`, 기본값은 `1`이다.
FCM만을 위해 이 변수를 켜면 S3 관련 변경도 함께 검토해야 하므로, 실제 설정과 plan을 확인한 뒤 별도로 조정한다.
이 FCM 루트는 AWS 설정을 변경하지 않는다.

참고: [Google WIF 설정](https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-other-clouds),
[ADC 구성 생성](https://docs.cloud.google.com/sdk/gcloud/reference/iam/workload-identity-pools/create-cred-config).

## 검증 범위

mock provider 테스트는 권한 범위·명시적 개발자 등록·API 유지·WIF 계정/역할 제한·프로젝트 번호 불일치와
와일드카드 역할 거부를 검사하며 실제 Google 호출을 하지 않는다.
실제 발송은 프로젝트·앱 등록·기기 토큰·서버 인증이 준비된 뒤 확인한다.

참고: [FCM 발송 권한](https://docs.cloud.google.com/iam/docs/roles-permissions/firebasecloudmessaging),
[로컬 ADC](https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment),
[Google IAM member](https://registry.terraform.io/providers/hashicorp/google/latest/docs/resources/google_project_iam).
