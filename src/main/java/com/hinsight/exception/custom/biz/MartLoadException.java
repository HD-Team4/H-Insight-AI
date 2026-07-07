package com.hinsight.exception.custom.biz;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

// S3/클래스패스 데이터 마트 로드에 모두 실패한 경우(폴백까지 실패).
public class MartLoadException extends BusinessException {
    public MartLoadException(Throwable cause) {
        super(ErrorCode.MART_LOAD_FAILED, cause);
    }
}
