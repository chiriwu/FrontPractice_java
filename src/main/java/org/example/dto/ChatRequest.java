package org.example.dto;

import java.util.List;

/**
 * 聊天请求:发送消息到指定会话
 * conversationId: 会话 id
 * messages: 消息对象数组,每条形如 {"role":"user","content":"..."}
 * model: 可选,覆盖默认模型名
 */
public class ChatRequest {

    private String conversationId;
    private List<Message> messages;
    private String model;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /** 单条消息:role(system/user/assistant) + content */
    public static class Message {
        private String role;
        private String content;

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}