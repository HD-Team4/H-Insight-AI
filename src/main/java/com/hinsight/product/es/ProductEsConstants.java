package com.hinsight.product.es;

/**
 * Elasticsearch 색인/동의어 관련 공용 상수.
 */
public final class ProductEsConstants {

    private ProductEsConstants() {}

    /** 상품 검색 인덱스 이름 */
    public static final String INDEX = "products";

    /** 동의어 세트 이름 (index-settings 의 synonyms_set 과 일치해야 함) */
    public static final String SYNONYM_SET = "product-synonyms";

    /** 인덱스 정의(JSON) 리소스 경로 */
    public static final String INDEX_DEFINITION = "elastic/product-index.json";
}
