-- 상품/라이브 이미지 URL CDN 전환 (KAN-120 브랜치에서 작업)
--   S3 직접 URL(hf4-s3.s3.ap-northeast-2.amazonaws.com)을 CloudFront CDN 도메인으로 교체한다.
--   영상(live_session.video_url)은 이미 CDN URL로 저장돼 있어 동일한 방식으로 통일.
--   CloudFront(d3czavkc472er9)는 hf4-s3 버킷 루트를 오리진으로 서빙하므로 호스트만 바꾸면 됨
--   (products_img/*, static/* 경로 모두 CDN에서 200 확인).

-- ========== MySQL (hf4_db) ==========

-- 1) 상품 이미지 (763건)
UPDATE products
SET image_url = REPLACE(image_url,
    'https://hf4-s3.s3.ap-northeast-2.amazonaws.com',
    'https://d3czavkc472er9.cloudfront.net')
WHERE image_url LIKE 'https://hf4-s3.s3.ap-northeast-2.amazonaws.com%';

-- 2) 라이브 세션 썸네일 (video_url은 이미 CDN)
UPDATE live_session
SET thumbnail_url = REPLACE(thumbnail_url,
    'https://hf4-s3.s3.ap-northeast-2.amazonaws.com',
    'https://d3czavkc472er9.cloudfront.net')
WHERE thumbnail_url LIKE 'https://hf4-s3.s3.ap-northeast-2.amazonaws.com%';

-- ========== PostgreSQL (벡터DB, 상품추천 챗봇 카드 이미지) ==========

UPDATE product_vectors
SET image_url = REPLACE(image_url,
    'https://hf4-s3.s3.ap-northeast-2.amazonaws.com',
    'https://d3czavkc472er9.cloudfront.net')
WHERE image_url LIKE 'https://hf4-s3.s3.ap-northeast-2.amazonaws.com%';

-- ========== 롤백 (필요 시 호스트 역치환) ==========
-- UPDATE products       SET image_url     = REPLACE(image_url,     'https://d3czavkc472er9.cloudfront.net', 'https://hf4-s3.s3.ap-northeast-2.amazonaws.com') WHERE image_url     LIKE 'https://d3czavkc472er9.cloudfront.net%';
-- UPDATE live_session   SET thumbnail_url = REPLACE(thumbnail_url, 'https://d3czavkc472er9.cloudfront.net', 'https://hf4-s3.s3.ap-northeast-2.amazonaws.com') WHERE thumbnail_url LIKE 'https://d3czavkc472er9.cloudfront.net%';
-- UPDATE product_vectors SET image_url    = REPLACE(image_url,     'https://d3czavkc472er9.cloudfront.net', 'https://hf4-s3.s3.ap-northeast-2.amazonaws.com') WHERE image_url     LIKE 'https://d3czavkc472er9.cloudfront.net%';
