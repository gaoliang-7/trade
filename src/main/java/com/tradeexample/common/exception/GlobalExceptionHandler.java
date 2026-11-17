package com.tradeexample.common.exception;

import com.tradeexample.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception) {

        ErrorCode errorCode = exception.getErrorCode();

        log.warn(
                "业务处理失败，code={}，message={}",
                errorCode.name(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(
                        errorCode.name(),
                        exception.getMessage()
                ));
    }

    /** 未预期异常返回通用500提示，堆栈留在服务端排查。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception) {

        log.error("请求处理出现未预期异常", exception);

        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(
                        errorCode.name(),
                        errorCode.getDefaultMessage()
                ));
    }

    /** 保留MVC原有HTTP状态和响应头，替换响应体；参数校验失败时汇总具体提示。 */
    @Override
    @Nullable
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {

        String code = switch (statusCode.value()) {
            case 400 -> ErrorCode.INVALID_ARGUMENT.name();
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND.name();
            default -> "HTTP_" + statusCode.value();
        };

        String message = switch (statusCode.value()) {
            case 400 -> ErrorCode.INVALID_ARGUMENT.getDefaultMessage();
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND.getDefaultMessage();
            case 405 -> "请求方法不支持";
            case 406 -> "无法提供请求要求的响应格式";
            case 413 -> "请求内容超过允许大小";
            case 415 -> "请求内容类型不支持";
            default -> "请求处理失败";
        };

        if (statusCode.is5xxServerError()) {
            code = ErrorCode.INTERNAL_ERROR.name();
            message = ErrorCode.INTERNAL_ERROR.getDefaultMessage();

            log.error("Spring MVC处理请求失败", exception);
        } else if (exception instanceof MethodArgumentNotValidException argumentException) {
            message = collectValidationMessages(
                    argumentException.getBindingResult().getAllErrors()
            );
        } else if (exception instanceof HandlerMethodValidationException methodException) {
            message = collectValidationMessages(
                    methodException.getAllErrors()
            );
        }

        ApiResponse<Void> response = ApiResponse.failure(code, message);

        return super.handleExceptionInternal(
                exception,
                response,
                headers,
                statusCode,
                request
        );
    }

    /** 去掉空白和重复的校验提示；没有可用提示时使用统一参数错误信息。 */
    private String collectValidationMessages(
            List<? extends MessageSourceResolvable> errors) {

        String message = errors.stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.joining("；"));

        return message.isBlank()
                ? ErrorCode.INVALID_ARGUMENT.getDefaultMessage()
                : message;
    }
}