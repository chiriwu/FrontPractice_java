package org.example.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

@Service
public class EmailService {

    private final Resend resend;
    private final String from;

    public EmailService(@Value("${resend.api-key}") String apiKey,
                        @Value("${resend.from}") String from) {
        this.resend = new Resend(apiKey);
        this.from = from;
    }

    public void sendVerifyCode(String to, String code) {
        CreateEmailOptions params = CreateEmailOptions.builder()
                .from(from)
                .to(to)
                .subject("注册验证码")
                .html("<p>你的验证码是 <strong>" + code + "</strong>,5 分钟内有效</p>")
                .text("你的验证码是 " + code + ",5 分钟内有效")
                .build();
        try {
            CreateEmailResponse data = resend.emails().send(params);
            System.out.println("邮件已提交, id=" + data.getId());
        } catch (ResendException e) {
            throw new IllegalStateException("邮件发送失败: " + e.getMessage(), e);
        }
    }
}