package com.hinsight.exception.custom.common;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

// 인증되지 않은 사용자가 로그인이 필요한 기능을 요청한 경우.
public class LoginRequiredException extends BusinessException {
    public LoginRequiredException() {
        super(ErrorCode.LOGIN_REQUIRED);
    }
}
