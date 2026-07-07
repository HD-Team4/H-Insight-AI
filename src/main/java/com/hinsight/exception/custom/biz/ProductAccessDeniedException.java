package com.hinsight.exception.custom.biz;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

// 기업 사용자가 자신 소유가 아닌 상품 데이터에 접근하려는 경우.
public class ProductAccessDeniedException extends BusinessException {
    public ProductAccessDeniedException() {
        super(ErrorCode.PRODUCT_ACCESS_DENIED);
    }
}
