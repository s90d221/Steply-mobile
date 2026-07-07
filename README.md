# Steply-Mobile Remote Camera Only

이 버전은 운동 분석은 PC 웹으로 넘기고, 모바일은 **프로필/개인 이력 저장 + 휴대폰 카메라 송출**을 담당합니다.

## 남긴 기능

- 로컬 프로필 등록/수정/선택
- Steply Web QR 스캔
- QR을 통한 웹 세션 + 모바일 프로필 연결
- CameraX 기반 휴대폰 카메라 미리보기
- 암호화 WebSocket(WSS) JPEG 프레임 송출
- PC final result 수신 후 로컬 이력 저장
- 세션 종료 시 PC 임시 개인 데이터 cleanup 요청

## 제거한 기능

- MediaPipe PoseLandmarker
- Chair Stand 자동 분석
- 모바일 내부 운동 체크 플로우

## 사용 흐름

```text
PC Steply Web 실행
→ QR 세션 만들기
→ 모바일 Steply 앱 실행
→ 프로필 등록/선택
→ 웹 QR 스캔
→ PC로 카메라 송출 시작
→ PC 분석 결과를 폰 로컬 이력에 저장
→ 송출 종료 시 PC 임시 캐시 삭제 요청
```

## 실행

```bash
./gradlew :app:assembleDebug
```

Android Studio에서는 프로젝트를 연 뒤 Gradle Sync 후 `app`을 실행하면 됩니다.

## 네트워크 조건

PC와 휴대폰은 같은 Wi‑Fi 또는 같은 로컬 네트워크에 있어야 합니다. Windows 방화벽이 Node.js 또는 3000번 포트를 막으면 PC 웹 화면에서 영상이 안 보일 수 있습니다.

QR/session payload와 PC 수신부 엔드포인트 규약은 `WEB_INTEGRATION_CONTRACT.md`를 단일 기준으로 따릅니다.
