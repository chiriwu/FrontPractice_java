package org.example.common;

import org.springframework.http.ResponseEntity;

/**
 * 统一响应包装:所有接口返回 {code, msg, data} 结构
 * 用法:
 *   ApiResponse.ok(data).toEntity()                     → 200
 *   ApiResponse.fail(ResultCode.UNAUTHORIZED).toEntity() → 401
 *   ApiResponse.fail(ResultCode.BAD_REQUEST, "自定义提示").toEntity()
 */
public class ApiResponse<T> {

    private final int code;
    private final String msg;
    private final T data;

    private ApiResponse(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ResultCode resultCode) {
        return new ApiResponse<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    /** 用自定义提示语覆盖枚举默认值,如 "用户名或密码错误" */
    public static <T> ApiResponse<T> fail(ResultCode resultCode, String customMsg) {
        return new ApiResponse<>(resultCode.getCode(), customMsg, null);
    }

    /** 业务码 → HTTP 状态码:0→200,401→401;无对应关系的自定义业务码统一 200,前端看 body.code */
    public int httpStatus() {
        if (code == ResultCode.SUCCESS.getCode()) {
            return 200;
        }
        try {
            return org.springframework.http.HttpStatus.valueOf(code).value();
        } catch (IllegalArgumentException e) {
            return 200;
        }
    }

    /** 一键转 ResponseEntity,HTTP 状态码自动跟随业务码 */
    public ResponseEntity<ApiResponse<T>> toEntity() {
        return ResponseEntity.status(httpStatus()).body(this);
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    public T getData() {
        return data;
    }
}
