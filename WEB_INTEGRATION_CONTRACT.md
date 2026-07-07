# Steply Web ↔ Mobile Remote Camera Contract

이 문서가 모바일 QR/session payload와 PC 수신부 엔드포인트 규약의 단일 기준입니다. README와 실행 가이드는 이 문서를 참조만 하고, QR 필드 정의를 중복 관리하지 않습니다.

## 1. QR 생성

웹은 QR payload를 생성합니다.

```json
{
  "type": "steply-web-session",
  "sessionId": "SESSION_ID",
  "serverUrl": "https://YOUR_PC_IP:3000",
  "expiresAt": "ISO_8601_UTC_EXPIRY",
  "pairingToken": "BASE64URL_128_BIT_RANDOM_ONE_TIME_TOKEN",
  "tlsCertSha256": "OPTIONAL_LOWERCASE_HEX_SHA256_OF_DER_LEAF_CERT"
}
```

- `serverUrl`은 반드시 `https://`여야 합니다. 모바일은 `http://` QR을 거부합니다.
- `expiresAt` 또는 `expiresAtEpochMs`가 필요합니다. 만료된 QR은 모바일에서 거부됩니다.
- `pairingToken`은 최소 128-bit 난수 기반 1회용 토큰이어야 합니다. PC는 `/connect`에서 토큰을 원자적으로 소비하고 재사용을 거부해야 합니다.
- 자체 서명 인증서를 쓰는 LAN 환경에서는 `tlsCertSha256`을 넣습니다. 값은 DER 인코딩된 leaf 인증서의 SHA-256 fingerprint를 lowercase hex 64자로 표현합니다. 인증서는 접속 IP/호스트를 SAN에 포함해야 합니다.

## 2. 프로필 연결

모바일은 QR 스캔 후 선택된 로컬 프로필을 웹 세션에 보냅니다.

`POST /api/session/{sessionId}/connect`

```json
{
  "sessionId": "SESSION_ID",
  "pairingToken": "BASE64URL_128_BIT_RANDOM_ONE_TIME_TOKEN",
  "profile": {
    "id": "local-profile-id",
    "displayName": "홍길동",
    "birthYear": 1950,
    "heightCm": 165,
    "mobilityNote": "필요 시 보호자 동행",
    "emergencyNote": "어지러우면 즉시 중단"
  }
}
```

모바일은 같은 값을 `X-Steply-Pairing-Token` 헤더에도 보냅니다. PC는 토큰 누락, 만료, 세션 불일치, 이미 사용된 토큰을 거부해야 합니다.

## 3. 카메라 송출

모바일은 아래 WebSocket으로 연결합니다.

```text
wss://PC_IP:3000/ws?sessionId=SESSION_ID&role=mobile
```

연결 후 모바일은 약 10fps로 JPEG binary frame을 전송합니다.
웹은 binary frame을 dashboard socket으로 broadcast해서 PC 화면에 표시합니다.

PC는 `/connect`가 성공해서 pairing token이 소비된 세션에 대해서만 mobile WebSocket 연결을 허용해야 합니다.

## 4. 결과 저장과 PC 임시 캐시

개인 이력의 영구 원본은 사용자 폰의 Room DB입니다. PC는 공용 컴퓨터일 수 있으므로 최종 결과를 디스크에 저장하지 않고, 세션 중 화면 표시와 모바일 전송을 위한 메모리 캐시만 유지해야 합니다.

PC가 분석을 완료하면 모바일 WebSocket으로 아래 메시지를 보냅니다.

```json
{
  "type": "final",
  "result": {
    "sessionId": "SESSION_ID",
    "userId": "local-profile-id",
    "testType": "chair_stand",
    "testLabel": "30 sec Chair Stand",
    "primaryValue": 10,
    "primaryLabel": "Chair Stands",
    "score": 82,
    "recommendationLevel": "practice_needed",
    "recommendations": [],
    "completedAt": 1783420800000
  }
}
```

모바일은 이 `result` JSON을 로컬 이력에 저장합니다. PC의 `/api/history`는 영구 이력이 아니라 세션 중 임시 표시 캐시만 반환해야 하며, 세션 종료 후 비어 있어야 합니다.

## 5. 세션 종료와 PC 데이터 삭제 요청

모바일은 사용자가 스트리밍을 중지하거나 카메라 화면을 떠날 때 PC에 임시 개인 데이터 삭제를 요청합니다.

```http
POST /api/session/{sessionId}/cleanup
X-Steply-Pairing-Token: PAIRING_TOKEN
Content-Type: application/json
```

```json
{
  "sessionId": "SESSION_ID",
  "pairingToken": "BASE64URL_128_BIT_RANDOM_ONE_TIME_TOKEN",
  "reason": "mobile-session-ended"
}
```

PC는 cleanup 요청 또는 모바일 WebSocket 연결 종료 시 해당 세션의 `profile`, `latestResult`, `finalResult`, 임시 history cache를 삭제해야 합니다. 응답은 아래 형태를 기준으로 합의합니다.

```json
{
  "ok": true,
  "session": {
    "id": "SESSION_ID",
    "profile": null,
    "latestResult": null,
    "finalResult": null
  }
}
```

## 6. 합동 검증 절차

1. PC 웹에서 새 QR 세션을 만든다.
2. 모바일에서 QR을 스캔하고 프로필을 연결한다.
3. 모바일 카메라 송출 후 PC 분석 결과를 모바일 이력에 저장한다.
4. 모바일에서 스트리밍을 중지하거나 화면을 벗어난다.
5. PC에서 `data/history.json`이 생성되지 않았는지 확인한다.
6. PC에서 `GET /api/history` 응답의 `items`가 비어 있는지 확인한다.
7. PC 대시보드가 `session-cleared`를 받아 프로필/결과/프레임 표시를 지웠는지 확인한다.
8. 모바일 이력에는 저장된 최종 결과가 남아 있는지 확인한다.

## 7. 웹/PC 팀 합의 필요

- QR payload에 `expiresAt` 또는 `expiresAtEpochMs`, `pairingToken`, 선택적 `tlsCertSha256`을 추가합니다.
- 모든 모바일 대상 API/WebSocket 엔드포인트를 HTTPS/WSS로 제공합니다.
- LAN 자체 서명 인증서를 사용할 경우 QR에 leaf 인증서 SHA-256 pin을 포함하고, 인증서 SAN에 PC IP/호스트를 포함합니다.
- `/api/session/{sessionId}/connect`는 `pairingToken`을 body와 `X-Steply-Pairing-Token` 헤더에서 확인하고 1회만 소비합니다.
- WebSocket은 `/connect`가 완료된 세션만 허용합니다.
- cleanup endpoint 이름과 method를 `POST /api/session/{sessionId}/cleanup`으로 확정할지 여부
- cleanup 인증을 기존 `pairingToken` 재사용으로 할지, 별도 cleanup token을 QR에 추가할지 여부
- 모바일 로컬 이력 저장용 final result JSON의 필수/선택 필드와 버전 필드
- cleanup 실패 시 모바일 재시도 정책과 사용자 안내 문구
