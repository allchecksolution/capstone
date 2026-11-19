# 국세청 사업자 진위확인 연결과 실제 검증

공식 자료: [공공데이터포털 서비스 안내](https://www.data.go.kr/data/15081808/openapi.do), [국세청 OpenAPI 명세](https://infuser.odcloud.kr/api/stages/28493/api-docs).

번호만 사용하는 상태조회(`/status`)와 세 가지 정보를 대조하는 진위확인(`/validate`)은 다릅니다. 기사 가입에서는 기존 정책대로 사업자등록번호·개업일자·대표자명이 일치하고 계속사업자인 경우만 허용합니다. 입력 정보의 일치 확인이며 신청자가 대표자 본인임을 증명하는 절차는 아닙니다.

## 공공데이터포털과 백엔드 설정

활용신청에서 **진위확인** 사용 가능 여부와 승인 상태를 확인합니다. 개인 활용신청 상세 화면과 인증키는 로그인한 본인만 확인할 수 있습니다. 일반 인증키의 **Decoding** 값을 백엔드 `NTS_SERVICE_KEY`에 설정합니다. Encoding 값을 넣으면 HTTP 클라이언트가 다시 인코딩하여 인증에 실패할 수 있습니다. Flutter 실행 인자·소스·커밋에는 서비스키를 넣지 않습니다.

기존 `JAVA_HOME`, `JWT_SECRET`, `KAKAO_APP_ID`, 테스트 DB 환경 변수를 설정한 PowerShell 창에서, 서버가 실행 중이면 Ctrl+C로 종료하고 실행합니다.

```powershell
$ntsKey = Read-Host "일반 인증키(Decoding)를 입력하세요" -AsSecureString
$env:NTS_SERVICE_KEY = [System.Net.NetworkCredential]::new('', $ntsKey).Password
cd D:\4-2\allcheckclone\capstone\apps\backend
.\gradlew.bat bootRun
```

새 환경 변수는 재시작 후 적용됩니다. `bootRun`은 `.env` 파일을 자동으로 읽지 않으므로 PowerShell 또는 IDE 실행 환경에 설정합니다.

## Android 기사 앱 연결

USB 연결 기기는 Android SDK의 `adb`로 포트를 연결합니다. 여러 기기가 있으면 `adb -s 기기ID reverse ...`로 대상을 지정합니다.

```powershell
adb reverse tcp:8080 tcp:8080
```

기사 앱의 IntelliJ Flutter 실행 설정에 다음 인자를 지정하고 완전히 다시 실행합니다. 기존 Native App Key를 사용합니다.

```text
--dart-define=KAKAO_NATIVE_APP_KEY=본인의_NATIVE_APP_KEY --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1 --dart-define=ALLCHECK_DEV_ACCESS=false
```

에뮬레이터는 역방향 포트 연결 대신 `http://10.0.2.2:8080/api/v1`도 사용할 수 있습니다. debug manifest에서만 로컬 HTTP를 허용하며 배포 환경은 HTTPS를 사용합니다. 개발용 홈에서는 외부 확인을 하지 않으므로 실제 검증은 `ALLCHECK_DEV_ACCESS=false`로 진행합니다.

## 화면 검증 순서

1. 카카오 로그인 후 `계속`을 누릅니다. 앱이 서비스 토큰을 발급받고 본인 가입 상태를 조회합니다. 신규 기사만 입력 화면으로 이동합니다.
2. 개인 정보 입력 후 사업자 정보 단계에서 등록번호, 등록증의 개업일자(`YYYY-MM-DD`), 대표자명을 입력합니다.
3. `사업자 정보 확인`을 누릅니다. 일치·계속사업자 결과가 확인되면 다음 단계로 이동할 수 있습니다.
4. 확인 후 번호·개업일자·대표자명 중 하나를 바꾸면 다시 확인해야 합니다.
5. 나머지 정보를 입력하고 동의 후 가입 신청합니다. 백엔드가 다시 국세청에 확인한 뒤 신청을 저장하고 승인 대기를 반환합니다. 반려 후 재신청에도 동일한 검증을 적용합니다.

가상의 번호로는 성공 검증을 할 수 없습니다. 실제 키와 사용 권한이 있는 사업자 정보를 사용합니다.

### 입력 양식 안내

가입 화면의 각 입력란 아래에 다음 안내를 표시합니다.

| 항목 | 입력 방법 |
|---|---|
| 사업자등록번호 | 숫자 10자리. `123-45-67890`처럼 입력한 하이픈은 앱에서 제거하여 전송 |
| 개업일자 | 등록증의 개업연월일을 `2020-01-15`로 입력. 백엔드가 국세청 요청 시 `20200115`로 변환 |
| 대표자명 | 등록증 표기 그대로 입력 |
| 사업체명 | 등록증의 상호 그대로 입력. `(주)`·`주식회사`가 있으면 유지하고, 없으면 임의로 추가하지 않음 |
| 회사 대표번호 | 숫자와 하이픈 사용. 예: `02-1234-5678` |

현재 국세청 대조 요청은 번호·개업일자·대표자명 세 필수 항목을 사용하며 상호 등 선택 항목은 보내지 않습니다. `p_nm2`는 외국인 사업자의 한글명용 선택 항목이므로 일반 대표자명을 중복해 넣지 않습니다.

HTTP `200`만으로 진위가 일치한 것은 아닙니다. 응답 `data[].valid`가 `01`인지, 운영 상태 `status.b_stt_cd`가 `01`인지 함께 확인합니다. `valid=02`는 HTTP 200이어도 정보 불일치이므로 서비스에서는 400으로 처리합니다. 포털과 앱의 차이를 확인하려면 요청 예시 외에 실제 응답 본문을 비교해야 합니다.

## API 계약과 오류

`POST /api/v1/drivers/business-verifications`는 기사 서비스 Access Token의 Bearer 인증을 요구합니다.

```json
{
  "businessRegistrationNumber": "1234567890",
  "openedOn": "2020-01-15",
  "representativeName": "대표자명"
}
```

위 값은 형식 예시입니다. 성공은 `200 {"status":"VERIFIED","verifiedAt":"..."}`입니다. 입력 단계 확인은 신청·승인을 생성하지 않습니다. 최종 제출에서는 클라이언트의 확인 여부를 신뢰하지 않고 다시 검증합니다. 기존 가입·재신청 계정별 60초 5회 제한은 유지하며, 입력 단계 확인에 별도의 새 요청 한도는 추가하지 않았습니다.

| 상황 | 결과 |
|---|---|
| 서비스 토큰 없음·만료·폐기 | 401 |
| 관리자 토큰으로 입력 단계 확인 | 403 |
| 누락·형식 오류·미정의 필드 | 400 |
| 정보 불일치·휴업·폐업 | 400 `BUSINESS_INFO_INVALID` |
| 키 미설정·외부 인증 실패·네트워크/응답 장애 | 503 `BUSINESS_VERIFICATION_UNAVAILABLE` |
| 최종 가입·재신청 한도 초과 | 429, `Retry-After` |

`DriverOnboardingBackend`의 실제 REST 연결 범위는 카카오 로그인·갱신·로그아웃·가입 상태 조회·사업자 확인·가입/재신청입니다. `DRIVER_NOT_FOUND`/404만 신규 기사로 해석합니다. 작업·위치·알림함 등 나머지 업무의 REST 어댑터는 별도 작업입니다.
