# Steply-Mobile 실행 가이드

## 목적

모바일 앱은 이제 분석 앱이 아니라 **원격 카메라 송출 앱**입니다.

## 모바일 화면 흐름

1. 프로필 선택 또는 등록
2. PC 웹 화면의 QR 스캔
3. 웹 세션에 프로필 연결
4. 카메라 권한 허용
5. `PC로 카메라 송출 시작` 버튼 클릭
6. PC final result 수신 시 폰 로컬 이력에 저장
7. 스트리밍 중지 또는 화면 이탈 시 PC 임시 캐시 삭제 요청

## QR/session 계약

QR payload 필드, 만료/1회용 토큰 검증, TLS pinning, 프로필 연결 API, WSS 프레임 송출 엔드포인트는 `WEB_INTEGRATION_CONTRACT.md`를 단일 기준으로 따릅니다.

모바일은 계약에 맞는 QR만 수락하고, 프로필 연결이 완료된 세션에 한해 암호화된 WebSocket으로 JPEG 카메라 프레임을 송출합니다.

개인 이력의 영구 원본은 사용자 폰입니다. PC는 세션 중 임시 표시 캐시만 유지해야 하며, 모바일은 세션 종료 시 `POST /api/session/{sessionId}/cleanup`을 호출해 PC 임시 개인 데이터 삭제를 요청합니다.
