package com.hinsight.exception.custom.review;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class ReviewPermissionException extends BusinessException {
    public ReviewPermissionException() {
        super(ErrorCode.REVIEW_PERMISSION_DENIED);
    }
}
