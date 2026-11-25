# 리모리 Android

기존 리모리 웹 화면을 Android WebView로 감싼 앱입니다. 웹 화면과 `/api`가 같은 원본에서 실행되므로 기존 이메일 로그인, HttpOnly 갱신 쿠키, 학습 데이터 저장 방식을 그대로 사용합니다. 웹 소스를 복제하지 않으며 웹을 수정하면 앱에도 반영됩니다.

## 로컬에서 실행

1. `../front`에서 `npm run dev`로 웹 서버를 실행합니다. 계정 기능을 쓰려면 기존 백엔드와 MySQL도 실행합니다.
2. Android Studio에서 **이 README가 있는 폴더**를 엽니다. Android SDK 37과 Build Tools 36.0.0, JDK 21 이상이 필요합니다. Android Studio에 포함된 JDK도 사용할 수 있습니다.
3. Android Studio에서 에뮬레이터를 실행하거나 USB 디버깅을 켠 휴대폰을 연결합니다.
4. 이 폴더에서 `./scripts/dev.sh`를 실행합니다. 연결된 Android의 `localhost:5173`을 컴퓨터의 웹 서버에 연결하고 앱을 설치·실행합니다.

한 번 연결한 뒤에는 Android Studio의 Run 버튼으로 실행할 수 있습니다. 기기를 재연결하면 `adb reverse tcp:5173 tcp:5173`을 다시 실행합니다. 여러 기기가 연결돼 있으면 `ANDROID_SERIAL=기기번호 ./scripts/dev.sh`로 선택합니다.

테스트용 APK는 `./gradlew :app:assembleDebug`로 생성되며 `app/build/outputs/apk/debug/app-debug.apk`에 있습니다. 현재 APK는 **로컬 개발용**이라 컴퓨터 웹 서버와 연결이 필요합니다. APK를 전달하는 것만으로 다른 사람이 인터넷에서 사용할 수 있게 되는 것은 아닙니다.

## 앱 동작

- 리모리 아이콘과 시작 화면, 시스템 바·키보드 여백 적용
- 홈에서 뒤로가기는 앱을 백그라운드로 전환, 다른 화면에서는 홈/덱 목록으로 이동, 팝업에서는 먼저 닫기
- 앱별 비회원 저장소와 로그인 쿠키 유지. 기존 Chrome의 비회원 기록은 앱에 자동 복사되지 않습니다.
- 엑셀 파일 선택과 양식 다운로드, 개인정보 처리방침·비밀번호 재설정 페이지 연결
- 설정에서 복습 알림과 시간을 기기별로 선택할 수 있습니다. 설정 시각에 복습할 카드가 있을 때 하루 한 번 알리고, 알림을 누르면 복습할 덱 선택 화면을 엽니다. 회원의 복습 여부는 서버 연결로 확인합니다.
- 연결 오류와 재시도, 외부 링크는 기본 브라우저로 열기
- Google 로그인·탈퇴 본인 인증은 Android Credential Manager 계정 선택창 사용

Google 계정 선택창은 함께 적용한 프런트엔드의 `src/platform/android.ts` 및 `NativeGoogleSignInButton.tsx`와 연결됩니다. 브라우저는 기존 Google Identity Services 버튼을 계속 사용합니다. 서버가 발급한 nonce를 Android에 전달하며 서버 검증과 계정 연결·학습 기록 이관 절차를 유지합니다. 네이티브 연결은 지정한 사이트 원본의 메인 프레임에서만 접근할 수 있습니다.

## Google 로그인 설정

기존 웹 OAuth 클라이언트와 **같은 Google Cloud 프로젝트**에 Android OAuth 클라이언트를 추가해야 합니다. 패키지명은 `com.remory.app`, 인증서 SHA-1은 `./gradlew :app:signingReport`에서 확인합니다. 현재 이 컴퓨터의 디버그 인증서 SHA-1은 `CE:05:50:CF:A8:67:36:46:24:62:0B:C1:F5:C8:9A:8E:F3:32:D1:F6`입니다. 실제 배포 시에는 Play App Signing의 인증서도 등록합니다. 서버의 웹 클라이언트 ID는 기존 `GOOGLE_CLIENT_ID`를 사용합니다. 이 설정과 Google 계정이 있는 기기에서 로그인·탈퇴 인증을 확인해야 합니다.

## 배포할 때

웹과 백엔드를 같은 HTTPS 도메인에 배포한 뒤 아래처럼 주소를 지정합니다.

```sh
./gradlew :app:bundleRelease -PremoryUrl=https://실제도메인/
```

배포 주소를 비워 두거나 HTTP로 지정하면 배포 빌드가 실패합니다. 운영 버전은 HTTP를 허용하지 않고 WebView 디버깅과 앱 데이터 자동 백업을 끕니다. 실제 스토어 제출 전에는 Android Studio의 **Generate Signed App Bundle**에서 배포 키로 서명해야 합니다. 서명 키는 저장소에 포함하지 않습니다. 이 프로젝트는 아직 스토어에 게시되지 않았습니다.

## 확인

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

원본 주소 검사 테스트에서 비슷한 도메인, 다른 포트, URL 사용자 정보, 비웹 스킴을 거부하는지 확인합니다. 프런트엔드의 Android 연결 테스트는 요청 매칭, nonce 전달, 취소·타임아웃과 늦게 도착한 인증 응답 무시를 검증합니다.
