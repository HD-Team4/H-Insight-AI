-- review_vectors RAG 고도화용 스키마 (PostgreSQL / pgvector)
-- 목적: 시간 가중치(recency) · 날짜 컷오프 필터 · 공식 스펙 우선 을 지원.
--
-- 실제 기존 스키마 (검증 완료, 3,000행 적재됨):
--   review_id  bigint PRIMARY KEY   (1~3000, 전부 채워짐)
--   product_id bigint               (전부 채워짐)
--   content    text
--   sentiment  varchar(20)
--   rating     smallint
--   embedding  vector(1024)
--   index: review_vectors_embedding_idx  hnsw (embedding vector_cosine_ops)
-- → review_id(PK)·product_id·content·embedding·HNSW 는 이미 있으므로 건드리지 않는다.
--
-- ※ written_at 은 "리뷰 작성일"이어야 한다. 기존 행은 아래 DEFAULT now() 로 들어가므로,
--   반드시 백필(POST /api/rag/sync-written-at)로 MySQL reviews.created_at 값을 채워야 한다.

-- 1) 부족한 메타 컬럼만 추가
ALTER TABLE review_vectors
    ADD COLUMN IF NOT EXISTS source_type     VARCHAR(20)  NOT NULL DEFAULT 'REVIEW',  -- 'REVIEW' | 'OFFICIAL'
    ADD COLUMN IF NOT EXISTS is_official      BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS written_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS renewal_version  VARCHAR(30);

-- 2) 인덱스
--  · 상품별 + 날짜 컷오프 필터 가속 (WHERE product_id=? AND written_at >= ?)
CREATE INDEX IF NOT EXISTS idx_rv_product_date
    ON review_vectors (product_id, written_at DESC);
--  · 공식 스펙만 빠르게 뽑기 위한 부분 인덱스
CREATE INDEX IF NOT EXISTS idx_rv_official
    ON review_vectors (product_id) WHERE is_official = TRUE;
--  · 공식 스펙 멱등 업서트 키: (product_id, renewal_version) 당 1건
CREATE UNIQUE INDEX IF NOT EXISTS uq_rv_official_ver
    ON review_vectors (product_id, renewal_version) WHERE is_official = TRUE;

-- (유저 리뷰 멱등 업서트는 기존 review_id PRIMARY KEY 를 그대로 ON CONFLICT 키로 사용한다.)
