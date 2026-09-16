package org.example;

import org.example.service.ChatService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class OllamaWarmupRunner implements ApplicationRunner {

    private final ChatService chatService;

    public OllamaWarmupRunner(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public void run(ApplicationArguments args) {
        chatService.warmup();
    }
}