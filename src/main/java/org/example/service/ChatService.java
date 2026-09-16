package org.example.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.example.dto.ChatRequest;
import org.example.entity.Conversation;
import org.example.entity.Message;
import org.example.mapper.ConversationMapper;
import org.example.mapper.MessageMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ChatService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;

    public ChatService(@Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
                       @Value("${ollama.model:deepseek-r1:1.5b}") String model,
                       ObjectMapper objectMapper,
                       MessageMapper messageMapper,
                       ConversationMapper conversationMapper) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.model = model;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
    }

    /**
     * 刷新会话的 update_time(每轮对话更新一次)
     */
    private void touchConversation(Long conversationId) {
        if (conversationId == null) return;
        Conversation update = new Conversation();
        update.setId(conversationId);
        update.setUpdateTime(LocalDateTime.now());
        conversationMapper.updateById(update);
    }

    /**
     * 查数据库获取会话历史消息,转成 Ollama 需要的格式
     */
    private List<Map<String, String>> loadHistory(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<Message> records = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByAsc(Message::getCreateTime));
        List<Map<String, String>> history = new ArrayList<>();
        for (Message m : records) {
            history.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        return history;
    }

    /**
     * 保存用户本次发送的消息(调用 Ollama 之前)
     */
    private void saveUserMessages(Long conversationId, List<ChatRequest.Message> messages) {
        if (conversationId == null) return;
        for (ChatRequest.Message m : messages) {
            Message record = new Message();
            record.setConversationId(conversationId);
            record.setRole(m.getRole());
            record.setContent(m.getContent());
            record.setCreateTime(LocalDateTime.now());
            messageMapper.insert(record);
        }
    }

    /**
     * 保存 AI 的完整回复(流式结束或非流式拿到结果后)
     */
    private void saveAssistantReply(Long conversationId, String content) {
        if (conversationId == null || content == null || content.isEmpty()) return;
        Message record = new Message();
        record.setConversationId(conversationId);
        record.setRole("assistant");
        record.setContent(content);
        record.setCreateTime(LocalDateTime.now());
        messageMapper.insert(record);
    }

    /**
     * 合并历史消息 + 本次新消息
     */
    private List<Map<String, String>> mergeMessages(Long conversationId,
                                                    List<ChatRequest.Message> newMessages) {
        List<Map<String, String>> all = new ArrayList<>(loadHistory(conversationId));
        for (ChatRequest.Message m : newMessages) {
            all.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        return all;
    }

    /** 非流式:一次性返回完整回复 */
    public String chat(String model, Long conversationId, List<ChatRequest.Message> messages) {
        List<Map<String, String>> allMessages = mergeMessages(conversationId, messages);
        saveUserMessages(conversationId, messages);   // 存用户消息
        touchConversation(conversationId);            // 刷新会话 update_time
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", allMessages,
                "stream", false,
                "keep_alive", "30m"    // 模型加载后在内存中保留 30 分钟,避免后续冷启动
        );
        String json = restClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(json);
            String reply = root.path("message").path("content").asText();
            saveAssistantReply(conversationId, reply);  // 存 AI 回复
            return reply;
        } catch (Exception e) {
            throw new IllegalStateException("Ollama 响应解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式:每收到一个增量片段就调用 onChunk 回调推给前端。
     * @param onChunk  收到片段时的回调,参数是该片段的文本
     * @param onDone   流结束时的回调
     * @param onError  出错时的回调
     */
    public void chatStream(String model, Long conversationId, List<ChatRequest.Message> messages,
                           AtomicBoolean active,
                           Consumer<String> onChunk, Runnable onDone, Consumer<Throwable> onError) {
        List<Map<String, String>> allMessages = mergeMessages(conversationId, messages);
        saveUserMessages(conversationId, messages);   // 存用户消息
        touchConversation(conversationId);            // 刷新会话 update_time
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", allMessages,
                "stream", true,          // ← 开启流式
                "keep_alive", "30m"    // 模型加载后在内存中保留 30 分钟,避免后续冷启动
        );
        // 累积"成功推送给前端"的片段,任何结束路径都存库(用户取消则存半截)
        StringBuilder fullReply = new StringBuilder();
        AtomicBoolean saved = new AtomicBoolean(false);
        // 幂等保存:只存一次;内容为空(报错/首字节前取消)时 saveAssistantReply 内部会跳过
        Runnable saveReply = () -> {
            if (saved.compareAndSet(false, true)) {
                saveAssistantReply(conversationId, fullReply.toString());
            }
        };
        // 用 exchange 拿到底层 InputStream,逐行读
        restClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        // 每次循环都检查客户端连接是否仍有效,断开则提前退出
                        while (active.get() && (line = reader.readLine()) != null) {
                            if (line.isBlank()) continue;
                            try {
                                JsonNode node = objectMapper.readTree(line);
                                String content = node.path("message").path("content").asText();
                                // log.info("收到 SSE 行: {}", line);

                                if (!content.isEmpty()) {
                                    onChunk.accept(content);    // 先推送,send 失败会把 active 置 false
                                    if (!active.get()) break;   // 客户端已断开:该片段前端没收到,不累积
                                    fullReply.append(content);  // 只累积确认推送成功的片段
                                }
                                if (node.path("done").asBoolean(false)) {
                                    saveReply.run();                 // 流结束:存完整 AI 回复
                                    if (active.get()) onDone.run();  // 正常结束才推 done
                                    return null;
                                }
                            } catch (Exception e) {
                                log.warn("解析流式片段失败: {}", line, e);
                            }
                        }
                        // 走到这里:用户取消(active=false)或上游流提前结束,把已推送的部分存库
                        saveReply.run();
                        if (active.get()) onDone.run();
                    } catch (Exception e) {
                        // Ollama 侧异常:先保存前端已经看到的半截内容,再上报错误
                        saveReply.run();
                        if (active.get()) onError.accept(e);
                    }
                    return null;
                });
    }

    /** 预热 Ollama 模型:加载到内存/显存,避免首次调用冷启动 */
    public void warmup() {
        try {
            // log.info("预热 Ollama 模型: {}", model);
            restClient.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", model,
                            "messages", List.of(Map.of("role", "user", "content", "hi")),
                            "stream", false,
                            "keep_alive", "30m"
                    ))
                    .retrieve()
                    .body(String.class);
            log.info("Ollama 预热完成");
        } catch (Exception e) {
            log.warn("Ollama 预热失败,启动继续: {}", e.getMessage());
        }
    }
}