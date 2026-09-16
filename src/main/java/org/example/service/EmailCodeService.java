package org.example.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

@Slf4j 
/**
 * 邮箱验证码服务:生成、存储、校验。
 * 学习期用 JVM 内存(ConcurrentHashMap)存码,重启即失效;生产环境换 Redis(SETEX + TTL)。
 */
@Service
public class EmailCodeService {

    private static final long TTL_MILLIS = 5 * 60 * 1000L;
    private static final long SEND_INTERVAL_MILLIS = 60 * 1000L;
    private static final int MAX_ATTEMPTS = 5;

    private final EmailService emailService;

    // email → 验证码条目。key 用 email 而不是 session:允许换浏览器/设备完成注册
    private final Map<String, CodeEntry> store = new ConcurrentHashMap<>();

    public EmailCodeService(EmailService emailService) {
        this.emailService = emailService;
    }

    /** 生成 6 位验证码 → 发邮件 → 存入内存。60 秒内禁止重发,防轰炸 */
    public void sendCode(String email) {
        CodeEntry old = store.get(email);
        long now = Instant.now().toEpochMilli();
        if (old != null && now - old.createdAt() < SEND_INTERVAL_MILLIS) {
            throw new IllegalStateException("发送太频繁,请 60 秒后再试");
        }
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        emailService.sendVerifyCode(email, code);
        store.put(email, new CodeEntry(code, now, 0));
    }

    /**
     * 校验验证码:不存在/已过期/不匹配都返回 false。
     * 成功后立即删除(一次性);失败累计到 5 次也删除,防暴力枚举 6 位数字。
     */
    public boolean verify(String email, String code) {
        CodeEntry entry = store.get(email);

        if (entry == null) {
            return false;
        }
        log.info("entry=",entry.code());

        if (Instant.now().toEpochMilli() - entry.createdAt() > TTL_MILLIS) {
            store.remove(email);
            return false;
        }
        if (entry.code().equals(code)) {
            store.remove(email);
            return true;
        }
        int attempts = entry.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            store.remove(email);
        } else {
            store.put(email, new CodeEntry(entry.code(), entry.createdAt(), attempts));
        }
        return false;
    }

    private record CodeEntry(String code, long createdAt, int attempts) {}
}
