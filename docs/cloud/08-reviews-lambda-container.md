# Step 8 — 리뷰 분석 Lambda 컨테이너 배포 (KAN-79)

집계 Lambda(Step 7)와 **동일하게 EventBridge 스케줄**로 리뷰 분석을 클라우드에서 자동 실행한다.
단, torch+KoELECTRA(~2GB)라 콘솔 붙여넣기가 안 됨 → **컨테이너 이미지(ECR)**.

```
[EventBridge 매일] → [Lambda 컨테이너: KoELECTRA] → 긍부정+키워드 → s3://hf4-datalake/mart/products/reviews.json
                      (모델을 이미지에 구워넣음)        ↑ RDS reviews 조회(pymysql)
```

- 코드: [lambda/reviews_analysis/](../../lambda/reviews_analysis/) — `handler.py`, `Dockerfile`, `requirements.txt`, `buildspec.yml`
- **S3 쓰기는 실행 역할 권한** 사용 → Lambda엔 액세스 키 불필요.
- **빌드는 CodeBuild(클라우드)** — 로컬 도커·CLI·액세스 키 전부 불필요. 아키텍처 **arm64(Graviton)**.

## 0~1. CodeBuild로 이미지 빌드 → ECR 푸시 (순수 콘솔)
콘솔엔 "이미지 업로드" 버튼이 없어 CodeBuild가 클라우드에서 대신 빌드한다. 소스는 S3 zip.

**① 소스 zip을 S3에 업로드**
- 업로드용 zip: `lambda/reviews_analysis` 의 4개 파일(Dockerfile·handler.py·requirements.txt·buildspec.yml)을 zip으로 묶은 것 (buildspec.yml이 zip 최상단).
- S3 콘솔 → `hf4-datalake` 버킷 → `codebuild/` 경로에 `reviews-build-src.zip` 업로드(드래그).

**② CodeBuild 프로젝트 생성** (CodeBuild 콘솔 → 빌드 프로젝트 → 프로젝트 생성)
- 이름: `hf4-reviews-build`
- **소스**: Amazon S3 → 버킷 `hf4-datalake` → S3 객체 키 `codebuild/reviews-build-src.zip`
- **환경**:
  - 관리형 이미지, OS **Amazon Linux**, 런타임 Standard, 이미지 **aarch64 standard**
  - 환경 유형 **ARM** (Lambda arm64와 일치)
  - **특권 부여(Privileged) 체크** ← docker 빌드 필수
  - 서비스 역할: **새 서비스 역할** (이름 기억: `codebuild-hf4-reviews-build-service-role`)
- **Buildspec**: "buildspec 파일 사용" (zip 안 buildspec.yml 자동 인식)
- 프로젝트 생성

**③ CodeBuild 서비스 역할에 ECR 권한 부여**
- IAM → 역할 → `codebuild-hf4-reviews-build-service-role` → 권한 추가 → **`AmazonEC2ContainerRegistryFullAccess`** 연결
- (ECR 리포는 buildspec이 없으면 자동 생성. `sts:GetCallerIdentity`는 기본 허용.)

**④ 빌드 시작**
- CodeBuild 프로젝트 → **빌드 시작** → 로그 실시간 확인 (모델 다운로드 포함 ~5–10분)
- 성공 시 ECR에 `hf4-reviews-analysis:latest` 생성됨 (ECR 콘솔에서 확인)

## 2. Lambda 실행 역할 (IAM)
IAM → 역할 생성 → **신뢰 주체: `lambda.amazonaws.com`** (S3 아님!) → 정책:
- `AWSLambdaBasicExecutionRole` (로그)
- `AmazonS3FullAccess` (또는 `hf4-datalake-s3-write` — 마트 S3 쓰기)
> Athena/Glue/ECR 불필요 (리뷰는 RDS 직접 조회, 이미지 pull은 Lambda가 알아서 함).
>
> 💡 앞서 만든 `hf4_for_sentiment` 역할을 재활용하려면 **신뢰 관계 편집 → `s3.amazonaws.com` → `lambda.amazonaws.com`** 으로 바꾸면 그대로 실행역할로 쓸 수 있다 (ECR 권한은 남아도 무해).

## 3. Lambda 함수 생성 (콘솔, 컨테이너 이미지)
- **함수 생성 → 컨테이너 이미지** → 이름 `hf4-reviews-analysis`
- **컨테이너 이미지 URI**: 이미지 찾아보기 → 위에서 푸시한 `:latest`
- **아키텍처: arm64** ← 반드시 arm64 (이미지와 일치)
- 실행 역할: 2번 역할
- **구성 → 일반 구성**: 타임아웃 **15분(900초)**, 메모리 **4096MB** (모델+추론 여유; 클수록 vCPU↑ 빨라짐)
- **구성 → 환경 변수** (`.env` 값 그대로, 비번은 이미지에 안 굽고 여기만):
  | 키 | 값 |
  |---|---|
  | `DB_HOST` | `hf4-db.caz9bnevtpvk.ap-northeast-2.rds.amazonaws.com` |
  | `DB_USER` | `admin` |
  | `DB_PASSWORD` | (`.env`의 DB_PASSWORD) |
  | `DB_NAME` | `hf4_db` |
  | `BUCKET` | `hf4-datalake` |
  | `MART_KEY` | `mart/products/reviews.json` |
  | `CONF_THRESHOLD` | `0.95` |

## 4. RDS 접근 확인 (중요)
Lambda는 VPC 밖(공용 인터넷)에서 RDS에 붙는다 → **NAT 불필요(비용 0)**, 대신:
- RDS **퍼블릭 액세스 = 예** (내 노트북에서 접속되면 이미 켜져 있음)
- RDS 보안그룹 **인바운드 3306** 이 Lambda를 허용해야 함. Lambda 공인 IP는 유동이라 → `0.0.0.0/0` 허용이 가장 간단 (데모 수준). 더 조이려면 VPC 구성 필요(그럼 S3/ECR용 엔드포인트 or NAT 비용 발생).

## 5. 테스트
- **테스트** → 빈 이벤트 `{}` → 실행 (콜드스타트 모델 로드로 첫 실행이 느림)
- 성공 시 `{"statusCode":200,"totalReviews":3000,"accuracy":96.5}` + 로그에 요약
- `s3://hf4-datalake/mart/products/reviews.json` 갱신 확인

## 6. EventBridge 스케줄
- **EventBridge → 규칙 생성 → 일정** → `rate(1 day)` (리뷰는 자주 안 변함)
- 대상: `hf4-reviews-analysis` Lambda

## 비용 (재확인)
- Lambda: 하루 1회 몇 분 → 프리티어(400,000 GB-초/월) 안 → **$0**
- ECR: 이미지 ~3GB × $0.10/GB → **~$0.30/월**
- S3/EventBridge/RDS: ~$0
- **합계 월 ~₩400. 상시 과금·GPU·상시 서버 전부 없음.**

## 다음
`mart/products/reviews.json` 이 S3에 생김 → 리뷰 분석 화면(`biz/reviewanalysis`, 현재 빈 스텁)이 이 마트를 읽어 렌더 (KAN-80 대시보드와 동일 방식). 이후 KAN-97: 이 마트+행동 마트 → Gemini 상품별 전략.
