# 3. 추가 제출 자료

## 내려받을 수 있는 자료

| 자료 | 저장소 경로 | Release 첨부 |
| --- | --- | --- |
| APK (release, 0.5.0 / versionCode 8) | [`apk/DANIM_DANIM_release.apk`](../apk/DANIM_DANIM_release.apk) | `DANIM_DANIM_release.apk` |
| 시연 영상 (4분 17초, 1920×1080, H.264·AAC) | [`demo/DANIM-demo-20261110.mp4`](../demo/DANIM-demo-20261110.mp4) | `DANIM-demo-20261110.mp4` |
| 스크린샷 26장 (차량 14, 웹 6, 모바일 3, AI 3) | [`docs/screenshots/`](screenshots) | `DANIM-screenshots.zip` |
| 소스 코드와 APK 압축 파일 | 저장소 전체 | Release의 "Source code (zip)" |

Release v1.0.0: https://github.com/oswarld/danim/releases/tag/v1.0.0 (앱 버전은 0.5.0 / versionCode 8입니다)

| 파일 | SHA-256 |
| --- | --- |
| `DANIM_DANIM_release.apk` | `f500541272d2d2b6134a6d73a1b978582de16701699421343a95be7cae1a368d` |
| `DANIM-demo-20261110.mp4` | `def8dccbaaf530cee0ca040b286801d6b0af250334178613d959130b17f039a8` |

APK는 공모전 시연용 데모 서명입니다(서명 인증서 SHA-256 `593f7af649110f81f77a372f169d0ed82d4a4d6d5ecddf6067cd44993c6979b9`). 상용 서명 키가 아닙니다.

## 공개 시연 링크

| 링크 | 내용 |
| --- | --- |
| https://danim.app | 소유자 서비스 (로그인 필요) |
| https://demo.danim.app/showcase | 공개 시연 리포트. 고정 합성 기록이며 계정이 필요 없습니다 |
| https://demo.danim.app/api/demo/mcp | 공개 시연 MCP. ChatGPT(개발자 모드)·Claude에 Streamable HTTP MCP로 추가하면 같은 고정 기록을 읽기 전용으로 조회합니다 |

## 시연 영상 구성

| 구간 | 내용 | 출처 |
| --- | --- | --- |
| 0:00–0:13 | 제목, 기획 의도 | 카드 |
| 0:13–3:13 | 출발 브리핑, 음성 질문, 주행, 도착 리포트, 충전 코치, 배터리 부족 안내와 충전소, 자동화 컨셉 | 공식 Pleos Connect 에뮬레이터에서 APK 0.5.0 녹화. 음성은 에뮬레이터 오디오 출력 |
| 3:13–3:19 | Gleo 연동 | 카드 |
| 3:19–3:40 | 웹: 개요, 주행, 충전 | 2026-10-31 로컬 검토 환경 녹화 (에뮬레이터 관측값 재생) |
| 3:40–4:10 | Claude와 ChatGPT의 공개 시연 MCP 호출 | 2026-10-29 실제 호출 녹화 |
| 4:10–4:17 | 맺음 | 카드 |

## 시연 범위

- 공식 Pleos Connect 2.1 에뮬레이터 기준이며 실차 검증 전입니다.
- 코파일럿의 일정·요금제는 화면에 '예시'로 표시된 값이고, 자동화는 아무것도 보내지 않는 컨셉입니다.
- 웹 화면 일부는 에뮬레이터에서 수집한 같은 관측값을 로컬 검토 환경에 재생한 사본입니다. 화면에 보이는 계정은 시험용 계정입니다.
- 공개 시연 리포트와 MCP의 기록은 고정 합성 기록이며 실제 차량의 관측값이 아닙니다.

## 이 저장소에 없는 것

서버(Supabase 백엔드·MCP 서버)와 Danim.app 웹 서비스의 소스는 포함하지 않았습니다. 비밀값(Pleos 클라이언트 시크릿, 서버 비밀 키, 차량 자격 증명, 서명 키)은 저장소와 APK 어디에도 없습니다.
