package org.example.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /user/** 接口鉴权：请求头 X-Api-Key 必须与 wx.api-key 一致。
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private final byte[] expectedKey;
    private final ObjectMapper objectMapper;

    public ApiKeyInterceptor(@Value("${wx.api-key}") String apiKey, ObjectMapper objectMapper) {
        this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String provided = request.getHeader("X-Api-Key");
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            response.setStatus(ResultCode.UNAUTHORIZED.getCode());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    ApiResponse.fail(ResultCode.UNAUTHORIZED, "密钥无效")));
            return false;
        }
        return true;
    }
}
