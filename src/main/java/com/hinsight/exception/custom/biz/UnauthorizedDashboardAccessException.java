package com.hinsight.exception.custom.biz;

import com.hinsight.exception.BusinessException;
import com.hinsight.exception.ErrorCode;

public class UnauthorizedDashboardAccessException extends BusinessException {
    public UnauthorizedDashboardAccessException() {
        super(ErrorCode.UNAUTHORIZED_DASHBOARD_ACCESS);
    }
}
