package com.aimall.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class AiChatTest {

    @Autowired
    private ChatClient chatClient;

    @Test
    public void testStreamWithFunctions() {
        System.out.println("Starting testStreamWithFunctions...");
        try {
            var flux = chatClient.prompt()
                    .user("有果15吗")
                    .functions("searchProducts")
                    .stream()
                    .content();

            StringBuilder sb = new StringBuilder();
            flux.doOnNext(chunk -> {
                System.out.print(chunk);
                sb.append(chunk);
            }).blockLast();
            System.out.println("\n--- STREAM FINISHED! Total chars: " + sb.length() + " ---");
        } catch (Exception e) {
            System.err.println("\n[CAUGHT EXCEPTION]: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }
}
