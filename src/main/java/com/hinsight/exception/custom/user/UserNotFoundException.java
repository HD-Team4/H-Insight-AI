package com.hinsight.exception.custom.user;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class UserNotFoundException extends BusinessException {
    public UserNotFoundException() {
        super(ErrorCode.USER_NOT_FOUND);
    }
}
