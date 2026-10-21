package com.aimall.ai;

import com.aimall.ai.service.AiShoppingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class AiChatTest {

    @Autowired
    private AiShoppingService aiShoppingService;

    @Test
    public void testChat() {
        System.out.println("Starting testChat...");
        try {
            var flux = aiShoppingService.chat("test_session_id", 1L, "有果15吗", null);
            flux.doOnNext(event -> System.out.println("[Flux Event] " + event))
                .doOnError(err -> System.err.println("[Flux Error] " + err.getMessage()))
                .blockLast();
        } catch (Exception e) {
            System.err.println("[Test Exception] " + e.getMessage());
            e.printStackTrace();
        }
    }
}
