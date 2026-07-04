# 클라우드 데이터 파이프라인 이식 (KAN-62)

로컬에서 완성한 **행동 로그 파이프라인**(App → Kafka → S3 Sink → 데이터레이크)을 AWS로 이식한다.
"로컬 페어리티" 전략 그대로 — 구조·설정은 동일하고 **엔드포인트만 클라우드로** 바뀐다.

## 목표 아키텍처 (MSK 기반)

```
[App 프로듀서] ──> [MSK (관리형 Kafka, VPC)] ──> [MSK Connect: S3 Sink] ──> [S3 데이터레이크]
   activity.*          RDS와 같은 VPC              Aiven S3 커넥터            raw/ ... mart/
```

- 리전: **ap-northeast-2 (서울)** — RDS와 동일 (`hf4-db...ap-northeast-2`)
- MSK는 RDS와 **같은 VPC**에 두어 앱이 둘 다 접근 가능하게 한다.

## 단계 (콘솔, 차근차근)

| # | 단계 | 티켓 | 상태 |
|---|---|---|---|
| 1 | **S3 데이터레이크 버킷 + IAM 정책** | KAN-67 | 👉 진행 중 |
| 2 | MSK 클러스터 생성 (VPC/서브넷/보안그룹, IAM 인증) | KAN-66 | 대기 |
| 3 | 토픽 생성 + 앱 프로듀서 MSK 연결 | KAN-69 | 대기 |
| 4 | MSK Connect S3 Sink (플러그인 업로드 + 커넥터) | KAN-68 | 대기 |
| 5 | E2E 검증 (주문 → activity.purchase → S3) | KAN-69 | 대기 |

## ⚠️ 비용 관리
- MSK는 **상시 과금**. 최소 구성(`kafka.t3.small` × 2, 스토리지 최소)으로 생성.
- MSK Connect도 MCU-시간 과금.
- **데모/발표 종료 후 반드시 삭제** (MSK 클러스터 → Connect → 삭제 순).

## 로컬 자산 재사용
| 로컬 | 클라우드 |
|---|---|
| MinIO | S3 (버킷 `hf4-datalake`) |
| docker Kafka | MSK |
| docker Connect + Aiven S3 sink | MSK Connect + 동일 Aiven 플러그인 |
| `activity-s3-sink.json` | 엔드포인트·인증만 교체해 재사용 |
