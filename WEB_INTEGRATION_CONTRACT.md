# Steply Web ↔ Mobile Stage 5 Contract

Web과 Mobile은 하나의 제품이며 다음 버전 계약을 함께 사용합니다.

- 평가 상태: `assessment_session.v2`
- 연결 최소 데이터: `steply_data_contract.v1`
- landmark 시계열: `landmark_series.v1`

Mobile Room이 개인 평가·운동·Care Agent·landmark의 영구 원본입니다. PC는 공용 장치일 수 있으므로 세션 중 메모리 mirror만 유지하며 `/api/history`는 `404`입니다.

## 1. QR과 인증

```json
{
  "type": "steply-web-session",
  "version": 3,
  "connectionSessionId": "SESSION_ID",
  "sessionId": "SESSION_ID",
  "assessmentSessionSchemaVersion": "assessment_session.v2",
  "serverUrl": "https://PC_IP:3000",
  "expiresAt": "ISO_8601_UTC_EXPIRY",
  "pairingToken": "BASE64URL_128_BIT_RANDOM_ONE_TIME_TOKEN",
  "tlsCertSha256": "OPTIONAL_DER_LEAF_SHA256"
}
```

Mobile은 HTTPS QR, 만료 시각, 1회용 pairing token을 검증합니다. LAN 자체 서명 인증서는 QR의 leaf certificate pin과 SAN을 함께 검증합니다. `/connect` body와 `X-Steply-Pairing-Token` 헤더가 모두 필요합니다.

## 2. 최소 프로필 연결

`POST /api/session/{sessionId}/connect`

```json
{
  "connectionSessionId": "SESSION_ID",
  "sessionId": "SESSION_ID",
  "pairingToken": "TOKEN",
  "assessmentSession": {
    "schemaVersion": "assessment_session.v2"
  },
  "dataContract": {
    "schemaVersion": "steply_data_contract.v1",
    "profile": {
      "id": "local-profile-id",
      "displayName": "홍길동",
      "birthYear": 1950,
      "sex": "FEMALE"
    },
    "recentAssessments": [
      {
        "assessmentSessionId": "ASSESSMENT_ID",
        "completedAt": 1783420800000,
        "risk": "MODERATE",
        "vulnerabilityIds": ["V7"],
        "valid": true,
        "chairStandRepetitions": 9,
        "balanceSecondsByStage": {
          "SIDE_BY_SIDE": 10.0,
          "SEMI_TANDEM": 10.0,
          "TANDEM": 7.25,
          "ONE_LEG": 0.0
        }
      }
    ],
    "generatedAt": 1783420800000
  }
}
```

`dataContract`와 내부 object는 additional property를 허용하지 않습니다. `recentAssessments`는 valid aggregate 최근 5회 이하이며 Chair Stand는 `cdcScoredRepetitions`, Balance는 정확한 네 자세 Double을 사용합니다. `height`, 메모, 안전 메모, `updatedAt`, 주간 리포트, 낙상, 안전 이벤트, Care Agent rationale은 PC에 보내지 않습니다.

## 3. 카메라와 평가 상태

Mobile은 인증된 세션에서만 다음 WSS로 nominal 30fps JPEG를 송출합니다.

```text
wss://PC_IP:3000/ws?sessionId=SESSION_ID&role=mobile
```

각 JPEG 앞에는 `camera-frame-meta`가 오며 처리 지연 시 오래된 프레임을 적재하지 않습니다. 연결 직후 Mobile은 `assessment-session.resume`을 보내고 Web은 전체 `assessment-session.updated` snapshot을 반환합니다. Mobile은 strict validation과 Room transaction이 성공한 뒤에만 `assessment-session.ack`을 전송합니다. message/result idempotency와 revision monotonicity를 강제합니다.

평가 snapshot은 3초 calibration, G1~G5, CDC Chair Stand, 4단계 Balance/F1~F5, sway 지표, V1~V9, 결정론적 처방을 보존합니다. invalid/tracking-failed attempt도 evidence로 보존하지만 accepted result와 추이에는 들어가지 않습니다. legacy `type: final`은 무시하고 aggregate update만 영속합니다.

## 4. Landmark 시계열

Web은 terminal attempt마다 다음 exact envelope를 보냅니다.

```json
{
  "type": "landmark-series.finalized",
  "schemaVersion": "landmark_series.v1",
  "messageId": "MESSAGE_ID",
  "profileId": "PROFILE_ID",
  "assessmentSessionId": "ASSESSMENT_ID",
  "attemptId": "ATTEMPT_ID",
  "resultId": "RESULT_ID",
  "series": {
    "schemaVersion": "landmark_series.v1",
    "seriesId": "SERIES_ID",
    "profileId": "PROFILE_ID",
    "assessmentSessionId": "ASSESSMENT_ID",
    "attemptId": "ATTEMPT_ID",
    "analysisSessionId": "ANALYSIS_ID",
    "resultId": "RESULT_ID",
    "assessmentType": "FOUR_STAGE_BALANCE",
    "status": "VALID",
    "targetFps": 30,
    "startedAt": 1783420800000,
    "completedAt": 1783420810000,
    "samples": []
  }
}
```

각 sample은 증가하는 `sequence`/`timestampMs`와 index 0~32의 `normalizedLandmarks`, `worldLandmarks`를 모두 포함합니다. raw frame/video는 저장하지 않습니다. Mobile은 canonical attempt/result linkage를 확인해 Room에 저장한 뒤 matching `landmark-series.ack`을 전송합니다. 평가 update보다 먼저 도착한 series만 bounded pending으로 보관하며 잘못된 payload는 재시도 queue에 넣지 않습니다.

## 5. 운동·리포트·프로필 삭제

- 한 `workoutSessionId` 아래 여러 exercise completion을 Room에 idempotent 저장합니다. UI는 선택 프로필의 Room Flow만 표시합니다.
- 주간 리포트는 Mobile-local typed assessment/workout/Care state에서 생성합니다. report SharedPreferences key와 WorkManager tag는 profile-scoped입니다. 주간 snapshot은 연결 payload에 포함하지 않습니다.
- 프로필 삭제는 assessment/receipt/summary/history/care/workout/completion/landmark/profile을 하나의 Room transaction으로 제거합니다. 해당 프로필의 report, 선택 설정, WorkManager 작업도 coordinator가 제거하며 다른 프로필은 보존합니다.

## 6. 종료와 PC cleanup

`POST /api/session/{sessionId}/cleanup`은 pairing token으로 인증합니다. 상단/시스템 back과 `End PC session`은 모두 이 경로를 거칩니다. 성공 ACK 뒤에만 Mobile active assessment를 해제하고 화면을 닫습니다. 실패하면 화면을 유지하고 retry를 허용하며, 성공한 cleanup은 중복 실행하지 않습니다. 일시적 WebSocket 단절이나 Activity 재생성은 cleanup 사유가 아닙니다.

## 7. 검증

1. Web의 exact schema/CJS 검사와 Mobile strict codec 검사를 모두 실행합니다.
2. Web 산출 assessment/landmark JSON을 Mobile codec→repository에 입력하는 cross-runtime E2E를 실행합니다.
3. Room v6→v7 migration, workout idempotency/profile isolation, profile purge isolation, report store isolation을 검사합니다.
4. Mobile unit test, AndroidTest compile, lint, debug build와 Web unit/integration/build를 모두 통과해야 완료로 인정합니다.
