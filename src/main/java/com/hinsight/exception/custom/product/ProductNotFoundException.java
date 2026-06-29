package com.hinsight.exception.custom.product;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class ProductNotFoundException extends BusinessException {
    public ProductNotFoundException() {
        super(ErrorCode.PRODUCT_NOT_FOUND);
    }
}
