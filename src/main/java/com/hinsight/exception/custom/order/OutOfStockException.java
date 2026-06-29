package com.hinsight.exception.custom.order;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class OutOfStockException extends BusinessException {
    public OutOfStockException() {
        super(ErrorCode.OUT_OF_STOCK);
    }
}
