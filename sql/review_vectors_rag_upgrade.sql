-- review_vectors RAG 고도화용 스키마 (PostgreSQL / pgvector)
-- 목적: 시간 가중치(recency) · 날짜 컷오프 필터 · 공식 스펙 우선 을 지원.
-- 대상: 기존 review_vectors (id, embedding vector(1024), content, ... 존재 가정)
--
-- ※ written_at 은 "리뷰가 실제 작성된 날짜"여야 한다 (DB 적재일 아님).
--   원천 데이터에 작성일이 없으면 이 값 백필이 최우선 선행 작업이다.

ALTER TABLE review_vectors
    ADD COLUMN IF NOT EXISTS product_id      BIGINT,
    ADD COLUMN IF NOT EXISTS source_type     VARCHAR(20)  NOT NULL DEFAULT 'REVIEW',  -- 'REVIEW' | 'OFFICIAL'
    ADD COLUMN IF NOT EXISTS is_official      BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS written_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS renewal_version  VARCHAR(30);

-- source_type 과 is_official 정합성 유지
UPDATE review_vectors SET is_official = TRUE  WHERE source_type = 'OFFICIAL' AND is_official = FALSE;
UPDATE review_vectors SET is_official = FALSE WHERE source_type = 'REVIEW'   AND is_official = TRUE;

-- 의미검색용 HNSW (이미 있으면 skip)
CREATE INDEX IF NOT EXISTS idx_rv_embedding
    ON review_vectors USING hnsw (embedding vector_cosine_ops);

-- 상품별 + 날짜 컷오프 필터 가속
CREATE INDEX IF NOT EXISTS idx_rv_product_date
    ON review_vectors (product_id, written_at DESC);

-- 공식 스펙만 빠르게 뽑기 위한 부분 인덱스
CREATE INDEX IF NOT EXISTS idx_rv_official
    ON review_vectors (product_id) WHERE is_official = TRUE;
