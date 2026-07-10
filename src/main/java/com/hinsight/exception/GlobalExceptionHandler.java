package com.hinsight.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.ConstraintViolationException;

/**
 * 전역 예외 처리기.
 * 컨트롤러 계층에서 처리되지 않고 올라온 예외를 일관된 {@link ErrorResponse} JSON 으로 변환한다.
 * 처리 우선순위는 "구체적인 핸들러 → 마지막 fallback" 순이며, 더 구체적인 @ExceptionHandler 가 우선한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 프로젝트 정의 예외(도메인 커스텀 예외의 공통 부모).
     * 예외마다 ErrorCode 에 상태/코드/메시지가 정의되어 있으므로 그대로 매핑한다.
     * 클라이언트 요청 문제(4xx)이므로 스택트레이스 없이 warn 으로만 남긴다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        // 4xx(요청 문제)는 스택트레이스 없이 warn, 5xx(서버/외부자원 문제)는 원인까지 error 로 남긴다.
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("[BusinessException] {} - {}", errorCode.getCode(), exception.getMessage(), exception);
        } else {
            log.warn("[BusinessException] {} - {}", errorCode.getCode(), exception.getMessage());
        }
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.from(errorCode));
    }

    /**
     * @Valid / @ModelAttribute 바인딩 검증 실패 (요청 바디·폼).
     * 첫 번째 필드 에러 메시지를 사용자에게 노출한다.
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ErrorResponse> handleValidation(BindException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        log.warn("[Validation] {}", message);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
    }

    /**
     * @RequestParam / @PathVariable 등 메서드 파라미터 제약(@Validated) 위반.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getMessage())
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        log.warn("[Validation] {}", message);
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
    }

    /**
     * 컨트롤러에서 직접 던진 ResponseStatusException.
     * 지정된 상태 코드는 유지하고, 응답 포맷만 ErrorResponse 로 통일한다.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        String message = exception.getReason() != null ? exception.getReason() : status.getReasonPhrase();
        log.warn("[ResponseStatus] {} - {}", status.value(), message);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.name(), message));
    }

    /**
     * 위에서 잡히지 않은 모든 예외에 대한 최종 fallback.
     * 내부 메시지를 그대로 노출하면 안 되므로 일반화된 메시지를 반환하고,
     * 원인 추적을 위해 스택트레이스는 error 로그로 남긴다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error("[Unexpected] 처리되지 않은 예외 발생", exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ErrorResponse.from(ErrorCode.INTERNAL_ERROR));
    }
}
