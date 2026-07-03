-- ═══════════════════════════════════════════════════════════════
-- KAN-73: 매출 집계 SQL 설계·검증 (DuckDB → 추후 Athena 이식)
--
-- 원천: S3 데이터레이크 raw 존 (gz JSONL) — 로컬은 MinIO를 s3:// 로 읽음
-- 차원: RDS 스냅샷 TSV (export 방법은 README 참고)
-- 실행: duckdb < agg_validation.sql   (dim TSV 경로는 환경에 맞게 수정)
--
-- 정제 규칙 (프로파일링으로 확정):
--   ① history_id 중복 제거          — CDC/브로커 at-least-once 재전송
--   ② 삭제 이벤트(__deleted) id 제외 — 삭제된 레코드는 insert 행까지 제거
--   ③ 수량 1~100 컷                 — 테스트 오염(수량 5천6백만 건) 차단
-- ═══════════════════════════════════════════════════════════════

INSTALL httpfs; LOAD httpfs;
SET s3_endpoint = 'localhost:9000';          -- 클라우드: s3.ap-northeast-2.amazonaws.com
SET s3_access_key_id = 'minioadmin';
SET s3_secret_access_key = 'minioadmin';
SET s3_use_ssl = false;
SET s3_url_style = 'path';

CREATE VIEW raw AS
SELECT * FROM read_json('s3://hf4-datalake/raw/purchase-history/**/*.gz',
                        format='newline_delimited', compression='gzip');

CREATE VIEW products AS SELECT * FROM read_csv('dim_products.tsv', delim='\t', header=true);
CREATE VIEW categories AS SELECT * FROM read_csv('dim_categories.tsv', delim='\t', header=true);

-- ── 정제 ──
CREATE VIEW clean AS
WITH dedup AS (
    SELECT *, row_number() OVER (PARTITION BY history_id ORDER BY created_at DESC) AS rn
    FROM raw
),
deleted_ids AS (SELECT DISTINCT history_id FROM raw WHERE __deleted = 'true')
SELECT history_id, user_id, product_id, quantity, price,
       price * quantity AS line_total,
       CAST(created_at AS DATE) AS d
FROM dedup
WHERE rn = 1
  AND __deleted = 'false'
  AND history_id NOT IN (SELECT history_id FROM deleted_ids)
  AND quantity BETWEEN 1 AND 100;

-- ── 0. 정제 전/후 검증 ──
SELECT (SELECT COUNT(*) FROM raw) AS raw_rows,
       (SELECT COUNT(*) FROM clean) AS clean_rows;

-- ── 1. KPI 요약 ──
SELECT ROUND(SUM(line_total)) AS total_revenue,
       COUNT(*) AS total_orders,
       COUNT(DISTINCT user_id) AS buyers,
       ROUND(SUM(line_total) / COUNT(*)) AS avg_order_value
FROM clean;

-- ── 2. 일별 매출 추이 (최근 14일) ──
SELECT d, ROUND(SUM(line_total)) AS revenue, COUNT(*) AS orders
FROM clean
WHERE d > (SELECT MAX(d) FROM clean) - INTERVAL 14 DAY
GROUP BY d ORDER BY d;

-- ── 3. 상품 매출 TOP 10 ──
SELECT c.product_id, p.product_name,
       SUM(c.quantity) AS units, ROUND(SUM(c.line_total)) AS revenue
FROM clean c JOIN products p USING (product_id)
GROUP BY 1, 2 ORDER BY revenue DESC LIMIT 10;

-- ── 4. 카테고리 점유율 ──
SELECT cat.category_name,
       ROUND(SUM(c.line_total)) AS revenue,
       ROUND(100.0 * SUM(c.line_total) / SUM(SUM(c.line_total)) OVER (), 1) AS share_pct
FROM clean c
JOIN products p USING (product_id)
JOIN categories cat USING (category_id)
GROUP BY 1 ORDER BY revenue DESC;

-- ── 5. 급등/급락 (최근 7일 vs 직전 7일, 최소 기준선으로 노이즈 제거) ──
WITH byweek AS (
    SELECT product_id,
           SUM(CASE WHEN d >  (SELECT MAX(d) FROM clean) - INTERVAL 7 DAY  THEN line_total ELSE 0 END) AS recent7,
           SUM(CASE WHEN d <= (SELECT MAX(d) FROM clean) - INTERVAL 7 DAY
                     AND d > (SELECT MAX(d) FROM clean) - INTERVAL 14 DAY THEN line_total ELSE 0 END) AS prev7
    FROM clean GROUP BY 1
)
SELECT p.product_name, ROUND(prev7) AS prev7, ROUND(recent7) AS recent7,
       ROUND(100.0 * (recent7 - prev7) / prev7, 1) AS growth_pct
FROM byweek b JOIN products p USING (product_id)
WHERE prev7 >= 100000
ORDER BY growth_pct DESC LIMIT 5;
