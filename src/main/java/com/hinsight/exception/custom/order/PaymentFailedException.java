package com.hinsight.exception.custom.order;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class PaymentFailedException extends BusinessException {
    public PaymentFailedException() {
        super(ErrorCode.PAYMENT_FAILED);
    }
}
