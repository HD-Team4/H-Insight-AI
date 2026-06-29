package com.hinsight.exception.custom.ai;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class VectorSearchException extends BusinessException {
    public VectorSearchException() {
        super(ErrorCode.VECTOR_SEARCH_FAILED);
    }
}
