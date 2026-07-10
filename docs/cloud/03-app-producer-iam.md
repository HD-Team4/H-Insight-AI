# Step 3 — 앱 프로듀서 → MSK (IAM 인증) (KAN-69 앞부분)

로컬 앱은 `kafka:29092`(평문)로 발행했지만, MSK는 **IAM 인증 + TLS**라 클라이언트 설정이 다르다.
바뀌는 건 **접속 설정뿐** — 발행 코드(`ActivityEventPublisher`)는 그대로.

## 1. 의존성 (build.gradle)
```gradle
implementation 'software.amazon.msk:aws-msk-iam-auth:2.2.0'
```
> MSK IAM 인증용 SASL 콜백 핸들러 제공.

## 2. 접속 설정 (application-prod.yml 등, 값은 .env/환경변수로)
```yaml
spring:
  kafka:
    bootstrap-servers: ${MSK_BOOTSTRAP}      # 콘솔 '클라이언트 정보'의 IAM용(:9098) 주소
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: AWS_MSK_IAM
      sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
      sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
```
로컬은 기존 평문 설정 유지 → 프로파일(`prod`)로 분리하면 로컬/클라우드 공존.

## 3. 접속 경로 — 둘 중 하나 (MSK는 VPC 내부라)
| 방식 | 설명 | 자격증명 |
|---|---|---|
| **(A) 앱을 EC2에 배포** (VPC 내) | 브로커에 사설망으로 접근. IAM은 **인스턴스 역할**로 자동 | 키 불필요 (권장) |
| **(B) MSK 퍼블릭 액세스 켜기** | 노트북 앱이 직접 접속 | 노트북에 AWS 자격증명 필요 (IAM SigV4 서명용) |

- IAM 인증은 **AWS 자격증명이 있어야** 서명함 — EC2(역할) 방식이 키 관리 없어 깔끔.
- 데모만 빠르게: EC2(또는 CloudShell)에서 **테스트 프로듀서**로 `activity.purchase` 몇 건 발행 → 파이프라인 검증도 가능.

## 4. 검증
앱(또는 테스트 프로듀서)에서 발행 → 다음 Step(Connect)이 S3로 적재하는지 확인.
