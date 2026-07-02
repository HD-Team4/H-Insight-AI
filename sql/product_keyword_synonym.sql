-- 유사검색 기능 테이블 (KAN-45)
--   1) product_keyword : 관리자가 상품별로 붙이는 검색 키워드 (OR 검색)
--   2) synonym_set     : 검색 동의어 사전 (denim,진,청바지,연청 ...)

-- 1) 상품별 검색 키워드
CREATE TABLE `product_keyword` (
  `keyword_id`  bigint      NOT NULL AUTO_INCREMENT,
  `product_id`  bigint      NOT NULL,
  `keyword`     varchar(50) NOT NULL COMMENT '관리자 지정 검색 키워드 (예: 러블리, 바지)',
  `created_at`  timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`keyword_id`),
  UNIQUE KEY `uk_product_keyword` (`product_id`, `keyword`),   -- 같은 상품에 중복 키워드 방지
  KEY `idx_product_keyword_keyword` (`keyword`),               -- "이 키워드가 붙은 상품" 역방향 조회
  CONSTRAINT `fk_product_keyword_product`
    FOREIGN KEY (`product_id`) REFERENCES `products` (`product_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='상품별 검색 키워드';

-- 2) 검색 동의어 사전
CREATE TABLE `synonym_set` (
  `synonym_id`  bigint       NOT NULL AUTO_INCREMENT,
  `terms`       varchar(500) NOT NULL COMMENT '동의어 그룹, 쉼표 구분 (예: denim,진,청바지,연청)',
  `is_active`   tinyint(1)   NOT NULL DEFAULT '1' COMMENT '사용 여부 (1=사용, 0=비활성)',
  `created_at`  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`synonym_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='검색 동의어 사전';
