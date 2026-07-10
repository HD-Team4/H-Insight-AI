package com.hinsight.exception.custom.user;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class InvalidPasswordException extends BusinessException {
    public InvalidPasswordException() {
        super(ErrorCode.INVALID_PASSWORD);
    }
}
