# DANIM (다님)

**내 차의 기록이 먼저 말을 거는 차량 AI 코파일럿.** Pleos 차량이 보고한 값을 자동으로 기록하고, 출발·주행·도착·충전의 순간마다 그 기록으로 계산한 답을 음성으로 알려 줍니다. 차 안의 답은 추측이 아닌 계산이고, 생성형 AI(ChatGPT·Claude)는 소유자가 허용한 기록만 읽도록 차 밖에서 연결합니다.

현대자동차그룹 차량용 앱 개발 공모전 제출용 공개 저장소입니다. 공식 Pleos Connect 에뮬레이터 기준의 시연이며 실차 검증 전입니다.

| 내려받기 | 위치 |
| --- | --- |
| APK (release, 0.5.0 / versionCode 8) | [`apk/DANIM_DANIM_release.apk`](apk/DANIM_DANIM_release.apk) 또는 [Release v1.0.0](https://github.com/oswarld/danim/releases/tag/v1.0.0) |
| 소스 코드와 APK 압축 파일 | [v1.0.0.zip](https://github.com/oswarld/danim/archive/refs/tags/v1.0.0.zip) |
| 시연 영상 (4분 17초, 1080p) | [`demo/DANIM-demo-20261110.mp4`](demo/DANIM-demo-20261110.mp4) 또는 Release 첨부 |
| 스크린샷 (차량·웹·모바일·AI) | [`docs/screenshots/`](docs/screenshots) 또는 Release 첨부 `DANIM-screenshots.zip` |

APK SHA-256: `f500541272d2d2b6134a6d73a1b978582de16701699421343a95be7cae1a368d` (공모전 시연용 데모 서명이며 상용 서명 키가 아닙니다).

Release v1.0.0은 공모전 제출본의 이름이고, 그 안의 앱 버전은 0.5.0(versionCode 8)입니다.

## 이 저장소의 구성

1. **앱 개발 내용과 UI/UX**: [`docs/01-app-development-ui-ux.md`](docs/01-app-development-ui-ux.md), 차량 앱 소스 [`apps/automotive`](apps/automotive)
2. **사용자 경험**: [`docs/02-user-experience.md`](docs/02-user-experience.md) (전기차 오너의 하루, 장면별 화면과 확인 범위)
3. **추가 제출 자료**: [`docs/03-additional-materials.md`](docs/03-additional-materials.md) (APK, 시연 영상, 스크린샷, 공개 시연 링크)

공개 범위: Pleos 차량 앱(Kotlin·Jetpack Compose)의 소스 전체와 APK, 문서, 영상을 공개합니다. 서버(Supabase 백엔드·MCP 서버)와 Danim.app 웹 서비스의 소스는 이 저장소에 포함하지 않았습니다. 차량 앱은 공개된 API 주소로 서버와 통신하며, 코파일럿은 계정과 서버 없이도 동작합니다.

## 사용한 Pleos Connect SDK

| SDK | 버전 | 앱에서 하는 일 |
| --- | --- | --- |
| Vehicle | 2.3.2 | 주행거리계·EV 배터리·연료 값을 읽어 실시간 화면과 주행 기록에 사용 |
| TextToSpeech | 3.3.1 (온디바이스) | 출발 브리핑, 질문 답변, 도착 리포트, 배터리 부족 안내를 음성으로 출력 |
| SpeechToText | 3.3.1 (온디바이스) | 음성 질문 인식 (한국어·영어) |
| NaviHelper | 3.0.5 | Pleos 내비게이션의 주변 충전소 화면 요청, 목적지 정보 조회 |
| Gleo Interface | App Schema | 읽기 전용 함수 3개(차량 브리핑·기록 질문·주변 충전소) 등록: [`schema.json`](apps/automotive/app/src/main/assets/schema.json) |

모두 공개 문서의 API이며 일반 권한만 선언합니다(`pleos.car.permission.CAR_DRIVE`, `CAR_ENERGY`, `TTS_SERVICE`, `STT_SERVICE`, `NAVI_ROUTE`, `NAVI_CUSTOM_MAP`). 차량 제어와 위치 코드는 없습니다. 음성은 온디바이스 모드라 APK와 저장소에 Pleos 클라이언트 시크릿이 없습니다.

## 테스트 환경

- 공식 Pleos Connect **2.1.0** 에뮬레이터 이미지 (Android Automotive, API 34, arm64-v8a)
- 화면 규격: **2560 × 1440 px, 160 dpi (가로)**
- 확인한 실행 구성: 8코어, 메모리 6144 MB, `-gpu host -no-snapshot`, 네트워크 연결. 소프트웨어 렌더링(`swiftshader_indirect`)에서는 에뮬레이터 플랫폼이 불안정했습니다.
- 차량 입력: 에뮬레이터의 공식 Virtual Console
- 필요한 설정: 내비게이션 연동에는 에뮬레이터의 데이터 네트워크가 필요합니다. `adb shell settings get global mobile_data`가 0이면 `adb shell svc data enable` 후 에뮬레이터를 한 번 재부팅합니다.

## 실행 방법

1. `adb -s <에뮬레이터> install apk/DANIM_DANIM_release.apk` 후 앱 목록에서 DANIM을 엽니다.
2. **코파일럿 (계정 불필요)**: 첫 화면의 "코파일럿 열기"를 누릅니다.
   - "출발" 탭의 "듣기": Pleos 음성으로 출발 브리핑을 읽습니다.
   - "묻기" 탭의 마이크 버튼: 음성 질문을 받습니다 (예: "이번 주 얼마나 탔어?").
   - "주행" 탭의 "주변 충전소 보기 (내비)": Pleos 내비게이션의 충전소 화면을 엽니다.
   - "충전" 탭: 지금과 경부하 시간의 충전 비용을 비교합니다.
   - "자동화" 탭: 아무것도 보내지 않는 컨셉 화면입니다.
   - 페어링 전에는 차량 값이 출처가 표시된 고정 시연 기록이고, 페어링 후에는 이 차량의 실시간 값입니다. 일정과 요금제는 화면에 '예시'로 표시됩니다.
3. **계정 없이 기록 보기**: "고정 기록 열기"를 누르면 출처가 표시된 고정 합성 기록(상태·주간 보고·정비·모바일·AI·로드맵)이 열립니다.
4. **실제 수집 보기**: https://danim.app 계정을 만든 뒤, 차량이 주차 상태일 때 앱의 두 항목에 동의하고 페어링 코드를 danim.app에서 입력합니다.
5. **차량 입력**: Virtual Console에서 기어를 D로 두고 ACCEL로 가속, BRAKE로 정지합니다. 페어링 후 코파일럿을 연 상태에서 주행하고 P단에 넣으면 도착 리포트를, 배터리를 20% 이하로 내리면 배터리 안내를 음성으로 들려줍니다. 주행 중에는 Pleos 정책에 따라 앱 화면이 가려지고 수집은 계속됩니다.
6. **AI 조회**: ChatGPT(개발자 모드) 또는 Claude에 Streamable HTTP MCP `https://demo.danim.app/api/demo/mcp`를 추가하면 고정 기록을 읽기 전용으로 조회합니다. 공개 리포트는 https://demo.danim.app/showcase 입니다.

## 소스에서 빌드

JDK 21, Android SDK 35(platform 35, build-tools 35.0.0)가 필요합니다. Gradle 8.10.2는 래퍼가 내려받고, Pleos SDK는 공개 Nexus 저장소에서 자격 증명 없이 받습니다.

```bash
cd apps/automotive
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

release 빌드는 `./gradlew :app:assembleRelease`이며, 서명 환경변수가 없으면 서명되지 않은 APK가 만들어집니다. 순수 Kotlin 모듈만 시험하려면 `-Pdanim.coreOnly=true`를 붙입니다(Android SDK 불필요).

| 모듈 | 내용 |
| --- | --- |
| `apps/automotive/core` | 순수 Kotlin. 코파일럿의 의도 분류와 답 계산, 주행 추적, 페어링·업로드 규칙, 고정 시연 기록. 단위 테스트 포함 |
| `apps/automotive/app` | Android 앱. Compose 화면(`ui`), 수집기(`collector`), Pleos 음성(`voice`), 내비 연동(`nav`), Gleo 수신(`gleo`) |

## 라이선스

이 저장소의 소스 코드, 문서, 이미지, 영상은 [CC BY 4.0](LICENSE)으로 공개합니다. 출처를 "DANIM (danim.app)"으로 표시하면 복제·배포·변경할 수 있습니다.

- Pleos Connect SDK는 저장소에 포함되어 있지 않으며 빌드할 때 Pleos Nexus에서 받습니다. SDK와 Android·Kotlin 라이브러리, 앱에 포함된 Asta Sans 글꼴은 각자의 라이선스를 따릅니다.
- CC BY 4.0은 상표권을 허락하지 않습니다(라이선스 2조). DANIM 이름과 로고를 상표로 쓰는 것은 포함되지 않습니다.
- 스크린샷과 영상에 보이는 Pleos Connect, ChatGPT, Claude의 화면과 상표는 각 권리자의 것입니다.
