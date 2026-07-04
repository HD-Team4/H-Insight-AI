-- ═══════════════════════════════════════════════════════════════
-- KAN-75: Athena 외부 테이블 (S3 raw = ActivityEvent 엔벨로프 포맷)
--
-- 이벤트 형식 (앱 프로듀서가 발행):
--   {"type":"purchase","userId":1,"occurredAt":"2026-07-02T05:43:11Z",
--    "payload":{"productId":1,"quantity":2,"price":39900}}
--
-- 파티션 프로젝션 사용 → MSCK REPAIR / 수동 파티션 추가 불필요 (년/월/일 자동 인식)
-- S3 경로: raw/activity.<type>/year=YYYY/month=MM/day=DD/*.gz
-- ═══════════════════════════════════════════════════════════════

CREATE DATABASE IF NOT EXISTS hf4_datalake;

-- ── 구매 이벤트 ──
CREATE EXTERNAL TABLE IF NOT EXISTS hf4_datalake.activity_purchase (
  type       string,
  userid     bigint,
  occurredat string,
  payload    struct<productid:bigint, quantity:int, price:double>
)
PARTITIONED BY (year int, month int, day int)
ROW FORMAT SERDE 'org.openx.data.jsonserde.JsonSerDe'
LOCATION 's3://hf4-datalake/raw/activity.purchase/'
TBLPROPERTIES (
  'projection.enabled'='true',
  'projection.year.type'='integer',  'projection.year.range'='2026,2030',
  'projection.month.type'='integer', 'projection.month.range'='1,12', 'projection.month.digits'='2',
  'projection.day.type'='integer',   'projection.day.range'='1,31',  'projection.day.digits'='2',
  'storage.location.template'='s3://hf4-datalake/raw/activity.purchase/year=${year}/month=${month}/day=${day}/'
);

-- ── 검색 이벤트 ──
CREATE EXTERNAL TABLE IF NOT EXISTS hf4_datalake.activity_search (
  type       string,
  userid     bigint,
  occurredat string,
  payload    struct<keyword:string, page:int, resultcount:bigint>
)
PARTITIONED BY (year int, month int, day int)
ROW FORMAT SERDE 'org.openx.data.jsonserde.JsonSerDe'
LOCATION 's3://hf4-datalake/raw/activity.search/'
TBLPROPERTIES (
  'projection.enabled'='true',
  'projection.year.type'='integer',  'projection.year.range'='2026,2030',
  'projection.month.type'='integer', 'projection.month.range'='1,12', 'projection.month.digits'='2',
  'projection.day.type'='integer',   'projection.day.range'='1,31',  'projection.day.digits'='2',
  'storage.location.template'='s3://hf4-datalake/raw/activity.search/year=${year}/month=${month}/day=${day}/'
);

-- ── 클릭 이벤트 ──
CREATE EXTERNAL TABLE IF NOT EXISTS hf4_datalake.activity_click (
  type       string,
  userid     bigint,
  occurredat string,
  payload    struct<productid:bigint, productname:string, brandname:string, price:int>
)
PARTITIONED BY (year int, month int, day int)
ROW FORMAT SERDE 'org.openx.data.jsonserde.JsonSerDe'
LOCATION 's3://hf4-datalake/raw/activity.click/'
TBLPROPERTIES (
  'projection.enabled'='true',
  'projection.year.type'='integer',  'projection.year.range'='2026,2030',
  'projection.month.type'='integer', 'projection.month.range'='1,12', 'projection.month.digits'='2',
  'projection.day.type'='integer',   'projection.day.range'='1,31',  'projection.day.digits'='2',
  'storage.location.template'='s3://hf4-datalake/raw/activity.click/year=${year}/month=${month}/day=${day}/'
);

-- ═══════════════════════════════════════════════════════════════
-- 예시 쿼리 (검증용) — payload 는 struct 라 점(.)으로 접근
-- ═══════════════════════════════════════════════════════════════

-- KPI: 총매출/주문수/구매자수 (최근 30일)
-- SELECT ROUND(SUM(payload.price * payload.quantity)) AS revenue,
--        COUNT(*) AS orders, COUNT(DISTINCT userid) AS buyers
-- FROM hf4_datalake.activity_purchase
-- WHERE date_parse(substr(occurredat,1,10),'%Y-%m-%d') > current_date - interval '30' day;

-- 인기 검색어 TOP 20
-- SELECT payload.keyword AS keyword, COUNT(*) AS cnt
-- FROM hf4_datalake.activity_search
-- GROUP BY payload.keyword ORDER BY cnt DESC LIMIT 20;

-- 상품별 클릭 수
-- SELECT payload.productid AS product_id, COUNT(*) AS clicks
-- FROM hf4_datalake.activity_click
-- GROUP BY payload.productid ORDER BY clicks DESC LIMIT 20;

-- 참고: 집계 → mart JSON 자동화는 Lambda(DuckDB)가 담당 (KAN-76, lambda/aggregate_mart/).
--       Athena 는 위처럼 애드혹 분석/검증용. Athena 결과 위치용 S3: s3://hf4-datalake/athena-results/
