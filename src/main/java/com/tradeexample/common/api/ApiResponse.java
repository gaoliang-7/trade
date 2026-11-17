package com.tradeexample.common.api;

/**
 * 接口统一响应，使用不可变record承载返回数据。
 * @param code 业务结果码，成功为SUCCESS
 * @param message 可直接展示给调用方的说明
 * @param data 成功时的业务数据，失败时为空
 * @param <T> 业务数据类型
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data
) {

    /** 将业务结果包装为成功响应，HTTP状态由Controller决定。 */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("SUCCESS", "操作成功", data);
    }

    /** 失败响应不携带业务数据，避免误用失败请求的中间结果。 */
    public static ApiResponse<Void> failure(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}