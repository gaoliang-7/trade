package com.tradeexample.common.exception;

import lombok.Getter;

/**
 * 接口错误分类
 */
@Getter
public enum ErrorCode {

    INVALID_ARGUMENT(400, "请求参数不合法"),

    RESOURCE_NOT_FOUND(404, "请求资源不存在"),

    BUSINESS_CONFLICT(409, "当前业务状态不允许此操作"),

    INTERNAL_ERROR(500, "服务处理失败，请稍后重试");

    private final int httpStatus;

    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}