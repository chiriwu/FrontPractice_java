package org.example;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.example.entity.Conversation;
import org.example.entity.Message;
import org.example.entity.User;
import org.example.mapper.ConversationMapper;
import org.example.mapper.MessageMapper;
import org.example.mapper.UserMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatHistoryController {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;

    public ChatHistoryController(ConversationMapper conversationMapper,
                                 MessageMapper messageMapper,
                                 UserMapper userMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.userMapper = userMapper;
    }

    /**
     * 新建对话会话:自动从登录态识别 userId,title 为空时用默认值
     */
    @PostMapping("/conversation")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createConversation(
            @RequestBody(required = false) Map<String, String> body) {

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.UNAUTHORIZED).toEntity();
        }

        String email = auth.getName();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (user == null) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.UNAUTHORIZED).toEntity();
        }

        String title = (body != null && body.get("title") != null) ? body.get("title") : "新对话";

        Conversation conversation = new Conversation();
        conversation.setUserId(user.getId());
        conversation.setTitle(title);
        conversation.setCreateTime(LocalDateTime.now());
        conversation.setUpdateTime(LocalDateTime.now());
        conversationMapper.insert(conversation);

        log.info("创建会话 conversationId={}, userId={}, title={}",
                conversation.getId(), user.getId(), title);

        return ApiResponse.ok(Map.of("id", (Object) conversation.getId())).toEntity();
    }

    /**
     * 插入聊天消息:role 必须为 user/assistant/system 之一
     */
    @PostMapping("/message")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createMessage(
            @RequestBody Map<String, String> body) {

        String conversationIdStr = body.get("conversationId");
        String role = body.get("role");
        String content = body.get("content");

        if (conversationIdStr == null || conversationIdStr.isBlank()) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "conversationId 不能为空").toEntity();
        }
        if (role == null || !role.matches("^(user|assistant|system)$")) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "role 必须是 user/assistant/system").toEntity();
        }
        if (content == null) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "content 不能为空").toEntity();
        }

        Long conversationId;
        try {
            conversationId = Long.parseLong(conversationIdStr);
        } catch (NumberFormatException e) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "conversationId 格式错误").toEntity();
        }

        Conversation existing = conversationMapper.selectById(conversationId);
        if (existing == null) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.NOT_FOUND, "会话不存在").toEntity();
        }

        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        messageMapper.insert(message);

        return ApiResponse.ok(Map.of("id", (Object) message.getId())).toEntity();
    }

    /**
     * 分页查询某会话的消息历史(按 create_time 升序,最早的在前)。
     * 会校验登录态 + 会话归属当前用户,防越权。
     *
     * @param conversationId 会话 ID
     * @param page          页码,从 1 开始,默认 1
     * @param size          每页条数,默认 20
     */
    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMessages(
            @RequestParam Long conversationId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {

        Long userId = currentUserId();
        if (userId == null) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.UNAUTHORIZED).toEntity();
        }

        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.NOT_FOUND, "会话不存在").toEntity();
        }
        if (!userId.equals(conversation.getUserId())) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.FORBIDDEN, "无权访问该会话").toEntity();
        }

        Page<Message> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getCreateTime);
        Page<Message> result = messageMapper.selectPage(pageParam, wrapper);

        List<Map<String, Object>> records = result.getRecords().stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "conversationId", m.getConversationId(),
                        "role", m.getRole(),
                        "content", m.getContent(),
                        "createTime", m.getCreateTime() == null ? "" : m.getCreateTime().toString()))
                .toList();

        return ApiResponse.ok(Map.of(
                "records", records,
                "total", result.getTotal(),
                "page", result.getCurrent(),
                "size", result.getSize())).toEntity();
    }

    /**
     * 从登录态取当前用户 id,未登录或用户不存在返回 null。
     */
    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, auth.getName()));
        return user == null ? null : user.getId();
    }
}
