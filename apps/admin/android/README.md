# 올체크 관리자 Android 앱

기존 관리자 프론트를 포함하는 Android WebView 앱입니다. 개발 서버 없이 실행하며 웹과 앱 모두 `../index.html`, `../style.css`, `../app.js`를 사용합니다. Gradle이 빌드할 때 세 파일만 앱 자산으로 복사합니다.

## Android Studio에서 실행

1. Android Studio에서 이 `android` 폴더를 엽니다.
2. Gradle JDK를 JDK 17 이상으로 설정합니다. Android Studio에 포함된 JDK를 사용할 수 있습니다.
3. SDK Manager에서 Android SDK Platform 36을 설치하고 Gradle 동기화를 완료합니다. 최초 동기화에는 인터넷 연결이 필요합니다.
4. 에뮬레이터 또는 USB 디버깅을 켠 Android 휴대폰을 선택한 후 Run을 누릅니다.

명령줄 빌드(Windows, JAVA_HOME 및 Android SDK 설정 필요):

```powershell
cd C:\Users\ong07\allcheck\capstone\apps\admin
npm.cmd run android:build
```

SDK를 찾지 못하면 Android Studio에서 프로젝트를 열어 SDK 경로를 설정하거나 `android/local.properties`에 `sdk.dir=C:/Users/ong07/AppData/Local/Android/Sdk`를 지정합니다. 이 파일은 Git에서 제외됩니다.

생성 APK: `app/build/outputs/apk/debug/app-debug.apk`

연결된 기기 설치:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r .\android\app\build\outputs\apk\debug\app-debug.apk
```

## 현재 동작

- 기존 모의 로그인과 MFA(테스트 코드 `123456`), 의뢰 등록·조회·통계·설정
- 앱 내부 저장소에 의뢰와 설정 유지. 기존 PC 브라우저 데이터와는 별개입니다.
- Android 뒤로 가기: 하위 화면에서 홈으로 이동, 홈에서 종료 확인
- 의뢰 취소 확인창 및 CSV 내보내기 시 Android 파일 저장 창
- 상태 표시줄·하단 시스템 영역에 화면이 가려지지 않도록 여백 적용
- 앱 아이콘과 이름 제공

## 이후 연결할 항목

인증·의뢰·채팅·알림은 기존 프론트의 모의 동작입니다. 음성파일 가져오기는 아직 추가하지 않았습니다. 백엔드 API가 정해지면 프론트의 localStorage 데이터 처리를 API 호출로 교체하고, 서버 통신 권한 및 허용 주소를 명시적으로 추가합니다. 현재 WebView는 앱에 포함한 파일만 허용합니다.

iOS 프로젝트는 포함하지 않습니다. 이 빌드는 로컬 확인용 debug 앱이며 스토어 배포용 서명은 포함하지 않습니다.

## 검증 상태

`npm.cmd test` 4개와 `npm.cmd run check` 통과. 2026-11-09 JDK 17로 `assembleDebug` 빌드 성공. APK v2 서명 검증 통과, 앱 이름·버전·포함 자산 확인 완료. APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 에뮬레이터 실행·실제 기기 설치는 아직 검증하지 않았습니다.

구성 참고: Android 공식 WebViewAssetLoader 및 Android Gradle Plugin 문서.
- https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader
- https://developer.android.com/build/releases/agp-8-13-0-release-notes
