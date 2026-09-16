package org.example;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.example.common.ApiResponse;
import org.example.common.ResultCode;
import org.example.dto.ChatRequest;
import org.example.entity.Conversation;
import org.example.entity.User;
import org.example.mapper.ConversationMapper;
import org.example.mapper.UserMapper;
import org.example.service.ChatService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j 
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ObjectMapper objectMapper;
    private final ConversationMapper conversationMapper;
    private final UserMapper userMapper;

    public ChatController(ChatService chatService, ObjectMapper objectMapper,
                         ConversationMapper conversationMapper, UserMapper userMapper) {
        this.chatService = chatService;
        this.objectMapper = objectMapper;
        this.conversationMapper = conversationMapper;
        this.userMapper = userMapper;
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

    /**
     * 流式接口快速返回错误:发一条 error 事件后关闭 emitter。
     */
    private SseEmitter errorEmitter(String msg) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event().name("error").data(msg));
            emitter.complete();
        } catch (IOException ignored) {}
        return emitter;
    }
    /**
     * 发送消息并获取 AI 回复。
     * body: { "conversationId": 1, "message": "你好" }
     */

    @PostMapping("noStream")
    public ResponseEntity<ApiResponse<Map<String, Object>>> chat(
        @RequestBody ChatRequest body) {

        String email = SecurityContextHolder.getContext().getAuthentication().getName();  
        String conversationIdStr = body.getConversationId();
        List <ChatRequest.Message> message = body.getMessages();
        String model = body.getModel();

        if (message == null  || message.isEmpty()) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "message 不能为空").toEntity();
        }

        Long conversationId;
        try {
            conversationId = Long.valueOf(conversationIdStr);
        } catch (NumberFormatException e) {
            return ApiResponse.<Map<String, Object>>fail(ResultCode.BAD_REQUEST, "conversationId 格式不正确").toEntity();
        }

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

        String reply = chatService.chat( model,conversationId, message);

        Map<String, Object> data = new HashMap<>();
        data.put("conversationId", conversationId);
        data.put("reply", reply);
        data.put("email", email);   // 顺带把当前用户返回,方便前端确认
        return ApiResponse.ok(data).toEntity();
    }
    /**
     * 流式聊天:SSE 逐行推送回复片段
     */
    @PostMapping
    public SseEmitter chatStream(@RequestBody ChatRequest body) {
        String conversationIdStr = body.getConversationId();
        Long conversationId;
        try {
            conversationId = Long.valueOf(conversationIdStr);
        } catch (NumberFormatException e) {
            return errorEmitter("conversationId 格式不正确");
        }

        List<ChatRequest.Message> messages = body.getMessages();
        String model = body.getModel();

        if (messages == null || messages.isEmpty()) {
            return errorEmitter("messages 不能为空");
        }

        Long userId = currentUserId();
        if (userId == null) {
            return errorEmitter("未登录");
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return errorEmitter("会话不存在");
        }
        if (!userId.equals(conversation.getUserId())) {
            return errorEmitter("无权访问该会话");
        }

        // SseEmitter 设置超时,0 表示永不超时(直到流结束)
        SseEmitter emitter = new SseEmitter(0L);

        // 标记连接是否仍然有效(客户端是否还在接收)
        AtomicBoolean active = new AtomicBoolean(true);

        // 客户端断开/超时/出错时,标记为不再活跃
        Runnable abort = () -> active.set(false);
        emitter.onCompletion(abort);
        emitter.onTimeout(abort);
        emitter.onError(ex -> active.set(false));

        // 流式读取必须在独立线程异步执行,Controller 立即返回 emitter
        CompletableFuture.runAsync(() ->
                chatService.chatStream(model, conversationId, messages, active,
                        chunk -> {
                            if (!active.get()) return;
                            try {
                                // JSON 字符串编码:避免 SSE 协议吃掉前导空格和换行
                                // 前端收到后用 JSON.parse(data) 还原
                                String encoded = objectMapper.writeValueAsString(chunk);
                                emitter.send(SseEmitter.event().name("chunk").data(encoded));
                            } catch (Exception e) {
                                active.set(false);
                                log.info("推送片段失败,客户端可能已断开: {}", e.getMessage());
                            }
                        },
                        // 流结束:发送 done 事件,再关闭连接
                        () -> {
                            if (active.get()) {
                                try {
                                    emitter.send(SseEmitter.event().name("done").data(""));
                                    emitter.complete();
                                } catch (Exception ignored) {}
                            }
                        },
                        // 出错:上报错误
                        ex -> {
                            if (active.get()) {
                                try { emitter.completeWithError(ex); } catch (Exception ignored) {}
                            }
                        }
                )
        );

        return emitter;
    }
}