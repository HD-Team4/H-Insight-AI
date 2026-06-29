package com.hinsight.exception.custom.user;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class DuplicateLoginIdException extends BusinessException {
    public DuplicateLoginIdException() {
        super(ErrorCode.DUPLICATE_LOGIN_ID);
    }
}
