package org.example;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.common.ApiResponse;
import org.example.entity.User;
import org.example.mapper.UserMapper;
import org.example.service.EmailService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SpringBootApplication
@RestController
public class Main {

    private final UserMapper userMapper;
    private final EmailService emailService;

    public Main(UserMapper userMapper, EmailService emailService) {
        this.userMapper = userMapper;
        this.emailService = emailService;
    }

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @GetMapping("/hello")
    public ResponseEntity<ApiResponse<Map<String, Object>>> hello(@RequestParam String param) {
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .select(User::getId, User::getEmail, User::getCreatedAt));

        Map<String, Object> data = new HashMap<>();
        data.put("param", param);
        data.put("total", users.size());
        data.put("users", users);
        return ApiResponse.ok(data).toEntity();
    }

    /**
     * 测试接口:验证 Resend 邮件发送链路。学习期把 code 一并返回,生产环境绝不能这么做
     */
}
