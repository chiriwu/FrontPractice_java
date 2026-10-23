package org.example.service;

import java.util.UUID;

import org.example.entity.UserSession;
import org.example.mapper.UserSessionMapper;
import org.springframework.stereotype.Service;

/**
 * 自定义登录态：token → openid 映射，持久化到 user_session 表，服务重启不失效。
 * openid 只由服务端在登录时绑定，后续请求客户端只传 token，防止水平越权。
 */
@Service
public class SessionService {

    /** 会话有效期：7 天 */
    private static final long TTL_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final UserSessionMapper userSessionMapper;

    public SessionService(UserSessionMapper userSessionMapper) {
        this.userSessionMapper = userSessionMapper;
    }

    public String createSession(String openid) {
        String token = UUID.randomUUID().toString().replace("-", "");
        UserSession session = new UserSession();
        session.setToken(token);
        session.setOpenid(openid);
        session.setExpireAt(System.currentTimeMillis() + TTL_MILLIS);
        userSessionMapper.insert(session);
        return token;
    }

    /**
     * 解析 token 对应的 openid；token 不存在或已过期返回 null（过期行顺手删除）。
     */
    public String resolveOpenid(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        UserSession session = userSessionMapper.selectById(token.trim());
        if (session == null) {
            return null;
        }
        if (session.getExpireAt() < System.currentTimeMillis()) {
            userSessionMapper.deleteById(session.getToken());
            return null;
        }
        return session.getOpenid();
    }
}
