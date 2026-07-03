# Step 1 — S3 데이터레이크 버킷 + IAM (KAN-67)

로컬 MinIO를 대체할 실제 S3 버킷을 만든다. 이후 모든 단계(Connect sink, Athena, 대시보드)가 이 버킷을 쓴다.

## 1-A. 버킷 생성 (S3 콘솔)

1. AWS 콘솔 → **S3** → **버킷 만들기**
2. **버킷 이름**: `hf4-datalake`
   - S3 이름은 **전 세계 유일**해야 함. 이미 있으면 `hf4-datalake-hd4` 같이 접미사.
   - ⚠️ 정한 이름을 이후 단계에서 계속 쓰니 메모.
3. **리전**: `아시아 태평양(서울) ap-northeast-2` (RDS와 동일)
4. **퍼블릭 액세스 차단**: **모두 차단 유지(ON)** — IAM으로만 접근
5. 버전 관리·암호화: 기본값
6. **버킷 만들기**

> 폴더(`raw/`, `mart/`)는 미리 안 만들어도 됨 — 커넥터가 객체를 쓸 때 자동 생성 (로컬 MinIO와 동일).

## 1-B. IAM 정책 생성 (IAM 콘솔)

MSK Connect(Step 4)가 이 버킷에 쓰려면 권한이 필요하다. 정책을 먼저 만들어 둔다.

1. AWS 콘솔 → **IAM** → **정책** → **정책 생성** → **JSON** 탭
2. 아래 붙여넣기 (`hf4-datalake`를 실제 버킷 이름으로 교체)
3. 정책 이름: `hf4-datalake-s3-write` → 생성

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "DatalakeObjectWrite",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:AbortMultipartUpload"],
      "Resource": "arn:aws:s3:::hf4-datalake/*"
    },
    {
      "Sid": "DatalakeBucketList",
      "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:GetBucketLocation"],
      "Resource": "arn:aws:s3:::hf4-datalake"
    }
  ]
}
```

> 이 정책은 Step 4에서 **MSK Connect 서비스 실행 역할**에 붙인다. (역할 자체는 커넥터 만들 때 생성)
> Athena(Step 이후)도 이 버킷 읽기가 필요하지만, 그건 해당 단계에서 별도 부여.

## ✅ Step 1 완료 기준
- [ ] `hf4-datalake` 버킷 생성됨 (서울 리전, 퍼블릭 차단)
- [ ] `hf4-datalake-s3-write` IAM 정책 생성됨
- [ ] 버킷 이름 확정·기록 (이후 단계에서 재사용)

완료되면 **버킷 이름**만 알려주면 Step 2(MSK 클러스터)로 진행.
