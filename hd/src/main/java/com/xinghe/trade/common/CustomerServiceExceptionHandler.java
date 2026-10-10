package com.xinghe.trade.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class CustomerServiceExceptionHandler {

     // 统一的异常日志入口：排查线上问题时只依赖这一处输出。
    private static final Logger log = LoggerFactory.getLogger(CustomerServiceExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        FieldError error = exception.getBindingResult().getFieldError();
        return ApiResponse.fail(error == null ? "请求参数无效" : error.getDefaultMessage());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> methodValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
        return ApiResponse.fail("请求参数无效");
    }

    // 阶段 A：订单状态冲突统一返回 409，便于前端区分业务失败和系统故障。
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    // 处理订单状态冲突、支付处理中等需要客户端重试或提示的业务异常。
    public ApiResponse<Void> conflict(IllegalStateException exception, HttpServletRequest request) {
         // 预期内的业务冲突：warn 级别，不打印完整栈，避免正常业务把日志刷满。
        log.warn("业务冲突 {}: {}", request.getRequestURI(), exception.getMessage());
        return ApiResponse.fail(exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    // 处理参数缺失、商品不存在和金额不匹配等请求错误。
    public ApiResponse<Void> badRequest(IllegalArgumentException exception, HttpServletRequest request) {
        log.warn("请求参数错误 {}: {}", request.getRequestURI(), exception.getMessage());
        return ApiResponse.fail(exception.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    // 处理跨用户查询、支付或会话访问，防止接口直接返回服务器错误。
    public ApiResponse<Void> forbidden(SecurityException exception, HttpServletRequest request) {
        // 越权访问要留痕：后续审计和排查都依赖这条记录。
        log.warn("越权访问被拒绝 {}: {}", request.getRequestURI(), exception.getMessage());
        return ApiResponse.fail(exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception, HttpServletRequest request) {
         // 非预期异常必须记录完整堆栈，否则 500 只返回通用文案会丢失唯一线索。
        log.error("接口未处理异常 {}", request.getRequestURI(), exception);
        // 响应体保持通用文案：不向客户端暴露表名、SQL 和类名等内部信息。
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务器内部错误"));
    }
}
