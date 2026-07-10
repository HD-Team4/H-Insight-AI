package com.hinsight.exception.custom.product;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

// Elasticsearch 등 검색 시스템 장애로 상품 검색에 실패한 경우.
public class ProductSearchException extends BusinessException {
    public ProductSearchException(Throwable cause) {
        super(ErrorCode.SEARCH_FAILED, cause);
    }
}
