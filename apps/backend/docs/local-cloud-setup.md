# 로컬 백엔드와 S3·FCM 개발 뼈대

백엔드와 PostgreSQL은 개발 PC에서 실행하고, 파일은 AWS S3에 저장하며 알림은 Google FCM으로 전송한다.
현재 단계는 설정 코드와 예시를 준비하는 단계다. 템플릿 작성만으로 클라우드 자원이 생성되지는 않는다.

```text
개발 PC의 Spring Boot ── HTTPS ── AWS S3
                    └─ HTTPS ── Google FCM ── 등록된 앱 기기
```

S3와 FCM은 백엔드에서 외부로 호출한다. 이 통신을 위해 백엔드에 공인 주소를 만들 필요는 없다.
실제 기기에서 로컬 백엔드 API를 호출할 경로는 별도로 준비해야 한다. 기기의 localhost는 개발 PC가 아니다.

## 1. S3 생성 준비

[S3 Terraform](../../../infra/terraform/s3/README.md)은 비공개 버킷, 암호화, HTTPS 강제,
녹음 덮어쓰기 방지와 녹음 접근 IAM 정책을 정의한다. 새 버킷을 만들 예정이므로 import는 필요하지 않다.

- `terraform.tfvars.example`을 비공개 `dev.tfvars`로 복사한 뒤 AWS 계정, 리전, 전역 고유 버킷 이름을 지정한다.
- Terraform 실행 인증은 외부에서 제공한다. 자격증명을 tfvars에 저장하지 않는다.
- 아직 배포 서버의 역할이 없으므로 `backend_role_name`은 지정하지 않아도 된다.
- 이 경우 녹음 접근 정책은 생성되지만 누구에게도 연결되지 않는다. 로컬 백엔드가 사용하는 AWS 주체에
  `recordings_policy_arn`의 정책을 연결하는 절차가 별도로 필요하다.
- Terraform 실행 주체와 백엔드 실행 주체는 용도가 다르다. 백엔드에는 버킷 관리 권한이 필요하지 않다.
- 브라우저가 직접 S3에 접근할 때만 명시적 CORS origin을 입력한다. 네이티브 앱은 CORS가 필요하지 않다.

먼저 init, validate, test로 구성을 확인하고, 실제 값을 정한 뒤 plan을 검토한다.
apply는 실제 버킷과 정책을 만드는 별도 단계다. 로컬 state는 보관해야 하며 공동 적용 전 공유·잠금 방식을 정한다.

## 2. 로컬 AWS 인증

`S3Config`와 `S3Presigner`는 AWS SDK 기본 자격증명 체인을 사용한다. 특정 인증 방식을 코드에 고정하지 않는다.
AWS 계정에 맞춰 프로필·임시 자격증명 등의 방식을 정하고, 백엔드 프로세스에도 인증정보를 제공한다.

Terraform 변수 `aws_profile`은 Terraform에만 적용된다. 백엔드에서 프로필을 사용하려면
백엔드 프로세스의 `AWS_PROFILE`도 별도로 설정한다. 이름이 같을 필요는 없다.
SSO·역할 전환 프로필을 선택하면 현재 build.gradle.kts에 없는 SSO/SSO OIDC/STS SDK 모듈이 필요한지 확인하고
선택한 방식에 필요한 의존성만 추가한다. AWS CLI 로그인 성공만으로 Java SDK 호출 성공이 보장되지는 않는다.

## 3. FCM 준비

FCM 서버는 Google이 운영한다. 백엔드용 서버를 클라우드에 배포하지 않아도 로컬에서 HTTP v1 API를 호출할 수 있다.
Firebase 프로젝트는 콘솔에서 최초 생성한다. 그 뒤 [FCM Terraform](../../../infra/terraform/fcm/README.md)으로
API, 발송 계정과 권한을 관리한다. 프로젝트 ID는 생성 후 개인 dev.tfvars에 입력한다.

준비 항목은 개발용 Firebase 프로젝트, FCM API, 발송 전용 서비스 계정, 발송 권한과 로컬 ADC 인증이다.
`FcmConfig`는 Google Application Default Credentials를 사용하며 비밀키를 코드에 넣지 않는다.
FCM Terraform은 명시한 개발자의 서비스 계정 impersonation을 지원한다. 해당 방식은 키 없이 단기 토큰을 얻는다.
그 밖의 인증이 필요하면 별도로 준비한다. 키 파일을 사용하는 경우에도 저장소 밖에 보관하고
`GOOGLE_APPLICATION_CREDENTIALS`에는 경로만 지정한다. 이 뼈대는 개인키를 생성하지 않는다.

Terraform을 실행하는 관리자의 인증과 백엔드 발송용 인증은 구분한다. 인증·권한이 준비되기 전에
`FCM_ENABLED=true`로 바꾸지 않는다. 실제 기기 앱도 같은 개발용 Firebase 프로젝트에 등록해야 한다.

### Android만 사용하는 경우

Firebase 프로젝트 하나에 관리자 Android 앱(`kr.allcheck.flutter.admin`)과 기사 Android 앱
(`kr.allcheck.flutter.driver`)을 각각 등록한다. Android 테스트에는 iOS 앱 등록이나 APNs 설정이 필요하지 않다.

프로젝트 ID 입력 위치는 다음 두 곳이다. `PASTE_FIREBASE_PROJECT_ID_HERE`를 같은 프로젝트 ID로 교체한다.
로컬 파일이 없다면 아래 예시를 복사해 만든다. 두 로컬 파일은 Git에서 제외한다.

| 용도 | 로컬 파일 | 입력 항목 | 복사할 예시 |
|---|---|---|---|
| Terraform | `infra/terraform/fcm/dev.tfvars` | `project_id = "..."` | [terraform.tfvars.example](../../../infra/terraform/fcm/terraform.tfvars.example) |
| 백엔드 | `apps/backend/scripts/local-cloud-env.ps1` | `$env:FCM_PROJECT_ID = '...'` | [local-cloud-env.ps1.example](../scripts/local-cloud-env.ps1.example) |

프로젝트 ID 입력과 인증은 별도다. 서비스 계정 impersonation을 사용할 개발자는 `dev.tfvars`의
`developer_members`에도 본인 Google 계정을 명시하고 Terraform을 적용해야 한다.
인증이 준비되면 로컬 스크립트의 `FCM_ENABLED`를 `true`로 바꾼다.

앱 설정에는 프로젝트 ID 외에도 Firebase가 발급하는 앱 ID, API 키 등이 필요하므로 직접 JSON을 만들지 않는다.
각 Android 앱의 `google-services.json`을 Firebase 콘솔에서 다운로드해 다음 위치에 넣는다.

| 앱 | 설정 파일 경로 |
|---|---|
| 관리자 | `apps/frontend/admin/android/app/google-services.json` |
| 기사 | `apps/frontend/driver/android/app/google-services.json` |

이 JSON 파일들도 Git에서 제외된다. 현재 앱은 네이티브 설정으로 `Firebase.initializeApp()`을 호출한다.
각 앱 디렉터리에서 실행할 때 `--dart-define=ALLCHECK_PUSH_ENABLED=true`를 추가한다.
실제 로그인·기기 등록을 위해 백엔드 주소와 기존 인증 설정도 준비해야 한다.

접수 알림의 `ReceptionPushDevices` 구현체와 프론트·백엔드 기기 등록 API 및 알림 데이터 형식 연동은
별도 구현 작업이다. 현재 상태에서 `RECEPTION_PUSH_ENABLED`는 `false`로 유지한다.

## 4. 백엔드 실행 설정

[PowerShell 예시](../scripts/local-cloud-env.ps1.example)를 같은 폴더의 `local-cloud-env.ps1` 또는
저장소 밖의 개인 `.ps1` 파일로 복사해 사용한다.
예시는 외부 연동을 모두 끈 상태이며 로그인, 자원 생성, 백엔드 실행을 자동 수행하지 않는다.

```powershell
# apps/backend에서 실행. 로컬 설정을 백엔드 프로세스에 전달한다.
. .\scripts\local-cloud-env.ps1
# PostgreSQL과 JWT_SECRET도 준비한 같은 터미널에서 실행한다.
.\gradlew.bat bootRun
```

Spring Boot는 `.env` 파일을 자동으로 읽지 않는다. IDE에서 실행한다면 동일한 값을 IDE 실행 환경에 입력한다.
스크립트를 실행한 터미널의 환경변수는 이미 실행 중인 IDE에 자동 전달되지 않는다.
기본 DB 설정은 application-local.yml, DB 실행은 infra/compose/compose.local.yml을 참고한다.

| 연동 | 준비 후 설정할 값 |
|---|---|
| S3 | `S3_ENABLED=true`, `S3_REGION`, `RECEPTION_RECORDING_BUCKET`, `S3_REQUEST_TIMEOUT`, AWS 인증 |
| FCM 전송 | `FCM_ENABLED=true`, `FCM_PROJECT_ID`, `FCM_CONNECT_TIMEOUT`, `FCM_REQUEST_TIMEOUT`, Google ADC |
| 접수 알림 워커 | 기기 등록 연동과 FCM 준비 후 `RECEPTION_PUSH_ENABLED=true`, `RECEPTION_PUSH_POLL_INTERVAL`, `RECEPTION_PUSH_CLAIM_TTL` |

예시 timeout과 poll 간격은 개발용 시작값이며 운영 정책이 아니다. 접수 claim TTL은 FCM 요청 timeout보다 길어야 한다.
AI 분석 워커는 별도 설정이 필요하다. S3·FCM 연결만으로 녹음 분석이 실행되지는 않는다.
JWT_SECRET과 기존 DB 설정은 이 예시가 생성하거나 대체하지 않는다.

## 5. 준비 완료 후 확인

1. 실제 소유권 인증을 거쳐 발급한 Presigned URL로 WAV를 업로드하고 완료 검증·재생을 확인한다.
2. 현재 세션에 연결된 기기를 등록하고 실제 업무 이벤트로 알림을 확인한다.
3. FCM 접수 성공과 기기 수신·사용자 확인을 구분한다. 앱은 권한 있는 API로 최신 업무 상태를 조회한다.

인증정보, FCM 기기 토큰, 서명 URL과 녹음 내용은 로그·Git·Terraform 출력에 남기지 않는다.
녹음 보존·삭제 주기와 미정인 재시도 정책은 이 뼈대에서 정하지 않는다.

## 6. 향후 AWS 배포

서버 배포 방식이 정해지면 실행 역할에 기존 녹음 정책을 연결하고, Google 발송 인증도 서버 환경에 맞게 구성한다.
AWS→Google Workload Identity Federation은 그 단계에서 검토한다. 개발자 개인 ADC나 장기 키를 서버 이미지에 복사하지 않는다.
운영용 버킷·Firebase 프로젝트와 state는 개발 환경과 분리한다.

참고: [AWS SDK 인증](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials.html),
[Google 로컬 ADC](https://docs.cloud.google.com/docs/authentication/set-up-adc-local-dev-environment),
[FCM HTTP v1](https://firebase.google.com/docs/cloud-messaging/send/v1-api).
