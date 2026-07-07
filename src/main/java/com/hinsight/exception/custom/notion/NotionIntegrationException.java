package com.hinsight.exception.custom.notion;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

/**
 * 노션 연동 관련 예외.
 * 미설정·페이지 미연결·이미지 누락·API 응답 오류 등 상황별 ErrorCode 를 받아 표현한다.
 */
public class NotionIntegrationException extends BusinessException {
    public NotionIntegrationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public NotionIntegrationException(ErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
