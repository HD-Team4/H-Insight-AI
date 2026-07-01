package com.hinsight.product.model.es;

import lombok.Data;

import java.util.List;

/**
 * Elasticsearch 상품 색인 문서.
 * 검색(매칭)에만 사용하며, 화면 표시용 데이터는 DB(products)에서 로드한다.
 * 필드명은 인덱스 매핑(product-index.json)과 그대로 일치시킨다(camelCase).
 */
@Data
public class ProductDocument {
    private Long productId;
    private Long categoryId;
    private Double price;
    private String productName;
    private String description;
    private List<String> keywords;
}
