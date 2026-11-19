# ACK-32 녹음 전사

`POST /internal/v1/transcribe`는 ACK-29 Backend 워커가 발급한 S3
Presigned GET URL을 사용합니다. 녹음 바이트의 크기와 SHA-256을 검증한 뒤
FFprobe로 재생 시간을 확인하고 FFmpeg로 WAV 오디오를 디코딩합니다. 검증을
통과한 원본을 CLOVA Speech 장문 동기 업로드 API로 보냅니다. 공급자의
`text` 값을 그대로 반환하며 임시 녹음은 요청 종료 시 삭제합니다.
S3 다운로드나 CLOVA 호출의 제한 시간이 지나면 `504 AI_TIMEOUT`을 반환합니다.
URL 만료 및 일반 다운로드 실패는 `502 RECORDING_ACCESS_FAILED`로 처리합니다.
저장할 수 없는 NUL 또는 잘못된 유니코드가 포함된 공급자 결과는 `502 AI_PROVIDER_FAILED`로 처리합니다.

## 실행 설정

아래 환경변수를 AI 프로세스에 주입합니다. `.env.example`에는 키 이름만
있으며, 앱은 파일을 자동으로 읽지 않습니다. Docker 실행 시 `--env-file`을
사용할 수 있습니다. Docker 이미지에는 FFmpeg와 FFprobe가 포함됩니다.
호스트에서 직접 실행하는 경우 두 실행 파일을 PATH에 추가합니다.

| 변수 | 설정값 |
|---|---|
| `AI_INTERNAL_API_KEY` | Backend의 `RECEPTION_AI_INTERNAL_API_KEY`와 동일한 값 |
| `AI_INTERNAL_NO_TRANSCRIBE_API_KEY` | 선택 사항. 전사 권한이 없는 별도 키에 `403 FORBIDDEN`을 반환. 누락·미등록·중복 헤더는 `401 UNAUTHENTICATED` |
| `AI_STT_S3_HOST` | Backend Presigner가 발급하는 정확한 버킷 호스트. 예 `example-bucket.s3.ap-northeast-2.amazonaws.com` |
| `AI_STT_S3_PATH_BUCKET` | path-style URL인 경우 정확한 버킷 이름. 이때 호스트에는 `s3.ap-northeast-2.amazonaws.com`처럼 S3 서비스 호스트를 설정 |
| `AI_STT_MAX_RECORDING_BYTES` | 운영 파일 크기 한도. 양의 정수, 최대 2,000,000,000 |
| `AI_STT_MAX_RECORDING_SECONDS` | 운영 녹음 길이 한도. 양수, 최대 7,200 |
| `AI_STT_DOWNLOAD_TIMEOUT_SECONDS` | S3 다운로드 시간 예산 및 소켓 타임아웃 |
| `AI_STT_WAV_TIMEOUT_SECONDS` | FFprobe와 FFmpeg에 할당할 총 시간 |
| `CLOVA_SPEECH_INVOKE_URL` | 장문 인식 도메인의 Invoke URL. `/recognizer/upload`는 앱에서 추가 |
| `CLOVA_SPEECH_SECRET_KEY` | CLOVA Speech 도메인 Secret Key |
| `CLOVA_SPEECH_TIMEOUT_SECONDS` | CLOVA 요청 시간 예산 및 소켓 타임아웃 |

운영 크기·길이·시간 값은 기본값 없이 설정합니다. 표의 상한은
[CLOVA Speech 장문 인식 사양](https://guide.ncloud-docs.com/docs/clovaspeech-spec)을
따릅니다. WAV 4종 MIME 별칭을 받고 실제 코덱은 설치된 FFmpeg로
검증합니다. 운용 녹음기의 코덱에 대한 CLOVA 인식 지원은 실제 샘플로
확인해야 합니다. Backend의 `RECEPTION_AI_STT_TIMEOUT`에는 세 단계의
시간 예산과 HTTP 오버헤드를 포함할 여유가 필요합니다. 워커 실행 예산에는
Backend 원문 저장과 후속 GPT 분석 시간도 포함합니다.

## 분석 API 연결

STT 성공 응답에는 접수·분석·녹음 식별자 및 버전, `SUCCEEDED`, 전사
문자열이 포함됩니다. Backend가 원문을 DB에 저장한 다음 생성한
`transcriptFileId`와 같은 원문을 `/internal/v1/analyze`에 전달합니다.
정상적인 분석 실행에는 ACK-24 분석 라우트가 함께 배포되어야 합니다.
