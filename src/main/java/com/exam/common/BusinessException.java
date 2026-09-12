package com.exam.common;

import lombok.Getter;

/**
 * 业务异常：Service 层抛出，由 GlobalExceptionHandler 统一转换为 ApiResponse。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;
    private final int httpStatus;

    public BusinessException(ResponseCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
        this.httpStatus = rc.getHttpStatus();
    }

    public BusinessException(ResponseCode rc, String message) {
        super(message);
        this.code = rc.getCode();
        this.httpStatus = rc.getHttpStatus();
    }
}
