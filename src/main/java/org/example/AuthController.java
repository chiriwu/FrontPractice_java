package org.example;

import java.util.HashMap;
import java.util.Map;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.example.entity.User;
import org.example.mapper.UserMapper;
import org.example.service.EmailCodeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // 把 SecurityContext 写进 HttpSession(key 为 SPRING_SECURITY_CONTEXT),
    // 后续请求的过滤器靠它恢复登录态
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailCodeService emailCodeService;

    public AuthController(UserMapper userMapper, PasswordEncoder passwordEncoder,
                          EmailCodeService emailCodeService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.emailCodeService = emailCodeService;
    }

    /**
     * 发送邮箱验证码:注册前调用。验证码 5 分钟有效,60 秒内不可重发
     */
    @PostMapping("/code/email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendEmailCode(
            @RequestBody Map<String, String> body) {

        String email = body.get("email");
        if (email == null || !email.matches("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$")) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "邮箱格式不正确").toEntity();
        }
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (count != null && count > 0) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.CONFLICT, "邮箱已被注册").toEntity();
        }
        try {
            emailCodeService.sendCode(email);
        } catch (IllegalStateException e) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, e.getMessage()).toEntity();
        }
        return ApiResponse.<Map<String, Object>>ok(Map.<String, Object>of("email", email)).toEntity();
    }

    /**
     * 登录:查数据库 → BCrypt 校验密码 → 构造 Authentication → 存入 Session → 种下 JSESSIONID Cookie
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {

        String email = body.get("email");
        String password = body.get("password");
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            log.warn("登录失败 email={}", email);
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "邮箱或密码错误").toEntity();
        }
        log.info("登录成功 userId={}, email={}", user.getId(), email);

        HttpSession session = buildSession(user.getEmail(), request, response);

        Map<String, Object> data = new HashMap<>();
        data.put("email", email);
        data.put("sessionId", session.getId());
        return ApiResponse.ok(data).toEntity();
    }

    /**
     * 注册:参数校验 → 邮箱唯一性校验 → 邮箱验证码校验 → BCrypt 加密入库 → 自动登录(写 Session + Cookie)
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {

        String email = body.get("email");
        String password = body.get("password");

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "邮箱和密码不能为空").toEntity();
        }
        if (!email.matches("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$")) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "邮箱格式不正确").toEntity();
        }
        if (password.length() < 6) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "密码长度不能少于6位").toEntity();
        }

        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (count != null && count > 0) {
            log.warn("注册失败:邮箱已存在 email={}", email);
            return ApiResponse.<Map<String, Object>>fail(ResultCode.CONFLICT, "邮箱已被注册").toEntity();
        }

        String code = body.get("code");
        if (code == null || code.isBlank()) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "验证码不能为空").toEntity();
        }
        log.info("code={},{}",code);
        if (!emailCodeService.verify(email, code)) {
            log.warn("注册失败:验证码错误或已过期 email={}", email);
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "验证码错误或已过期").toEntity();
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        userMapper.insert(user);

        HttpSession session = buildSession(email, request, response);

        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("email", email);
        data.put("sessionId", session.getId());
        return ApiResponse.ok(data).toEntity();
    }

    /**
     * 构造认证信息、写入 Session、种 JSESSIONID Cookie,返回当前 Session。
     * login 与 register 复用,避免重复。
     */
    private HttpSession buildSession(String email, HttpServletRequest request, HttpServletResponse response) {
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(email, null, AuthorityUtils.NO_AUTHORITIES);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authToken);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(300 * 60);

        Cookie jsession = new Cookie("JSESSIONID", session.getId());
        jsession.setPath("/");
        jsession.setHttpOnly(true);
        jsession.setMaxAge(-1);
        jsession.setAttribute("SameSite", "Lax");
        response.addCookie(jsession);
        return session;
    }

    /**
     * 校验登录态:/auth/me 不在白名单,能执行到这里说明过滤器已验证过 Cookie
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(HttpServletRequest request) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.UNAUTHORIZED).toEntity();
        }

        HttpSession session = request.getSession(false);
        Map<String, Object> data = new HashMap<>();
        data.put("email", auth.getName());
        if (session != null) {
            data.put("sessionId", session.getId());
            data.put("expireInSeconds", session.getMaxInactiveInterval());
        }
        return ApiResponse.ok(data).toEntity();
    }
}
