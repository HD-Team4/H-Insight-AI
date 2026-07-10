package com.hinsight.exception.custom.ai;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class EmbeddingFailedException extends BusinessException {
    public EmbeddingFailedException() {
        super(ErrorCode.EMBEDDING_FAILED);
    }

    public EmbeddingFailedException(Throwable cause) {
        super(ErrorCode.EMBEDDING_FAILED, cause);
    }
}
