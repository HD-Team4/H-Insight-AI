package com.hinsight.exception;

public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    // 외부 자원/파싱 실패 등 원인 예외를 감쌀 때 사용한다.
    // 클라이언트에는 ErrorCode 의 정제된 메시지만 나가고, 원인은 로그 추적용으로만 보존된다.
    protected BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
