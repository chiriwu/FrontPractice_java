package org.example.service;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 微信小程序登录：用 wx.login 的 code 换取 openid。
 */
@Service
public class WeChatService {

    private static final Logger log = LoggerFactory.getLogger(WeChatService.class);
    private static final String CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    private final String appid;
    private final String secret;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeChatService(@Value("${wx.appid}") String appid,
                         @Value("${wx.secret}") String secret,
                         ObjectMapper objectMapper) {
        this.appid = appid;
        this.secret = secret;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restTemplate = new RestTemplate(factory);

        if (secret == null || secret.isBlank()) {
            log.error("wx.secret 为空（环境变量 WX_APP_SECRET 未注入），微信登录会失败");
        }
    }

    public boolean supportsAppId(String appid) {
        return this.appid != null && this.appid.equals(appid);
    }

    /**
     * 调用微信 jscode2session，返回 openid。
     * code 为一次性临时凭证，不能重复使用。
     */
    public String code2Session(String code) {
        if (secret == null || secret.isBlank()) {
            throw new WeChatException("服务端未配置 AppSecret");
        }

        String url = UriComponentsBuilder.fromHttpUrl(CODE2SESSION_URL)
                .queryParam("appid", appid)
                .queryParam("secret", secret)
                .queryParam("js_code", code)
                .queryParam("grant_type", "authorization_code")
                .encode()
                .toUriString();

        String raw;
        try {
            // 微信失败时返回 text/plain，先按字符串接收再自行解析 JSON
            raw = restTemplate.getForObject(url, String.class);
        } catch (RestClientException e) {
            log.error("调用微信 code2session 失败", e);
            throw new WeChatException("微信接口请求失败");
        }

        if (raw == null || raw.isBlank()) {
            throw new WeChatException("微信接口无响应");
        }

        Map<?, ?> body;
        try {
            body = objectMapper.readValue(raw, Map.class);
        } catch (JsonProcessingException e) {
            log.error("解析微信响应失败", e);
            throw new WeChatException("微信接口返回格式异常");
        }

        Object openid = body.get("openid");
        if (openid != null && !String.valueOf(openid).isBlank()) {
            return String.valueOf(openid);
        }

        Object errcode = body.get("errcode");
        Object errmsg = body.get("errmsg");
        log.warn("微信 code2session 返回错误: errcode={}, errmsg={}", errcode, errmsg);
        throw new WeChatException("微信登录失败: " + (errmsg == null ? "未知错误" : errmsg)
                + (errcode == null ? "" : " (" + errcode + ")"));
    }

    public static class WeChatException extends RuntimeException {
        public WeChatException(String message) {
            super(message);
        }
    }
}
