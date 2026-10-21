package com.aimall.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiStreamFunctionCallingHelper;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ToolCall;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionMessage.ChatCompletionFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
public class AiChatTest {

    @Autowired
    private ChatClient chatClient;

    @Test
    public void testEmptyIdSplitBug() {
        OpenAiStreamFunctionCallingHelper helper = new OpenAiStreamFunctionCallingHelper();
        
        // Chunk 1: id="call_1", name="searchProducts", arguments=""
        var tc1 = new ToolCall("call_1", "function", new ChatCompletionFunction("searchProducts", ""));
        var msg1 = new OpenAiApi.ChatCompletionMessage("", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null, List.of(tc1), null, null);
        var chunk1 = new ChatCompletionChunk("id1", List.of(new ChatCompletionChunk.ChunkChoice(null, 0, msg1, null)), 1L, "m", null, null, null, null);

        // Chunk 2: id="", name=null, arguments="{\"keyword\": \"15\"}"
        var tc2 = new ToolCall("", "function", new ChatCompletionFunction(null, "{\"keyword\": \"15\"}"));
        var msg2 = new OpenAiApi.ChatCompletionMessage("", OpenAiApi.ChatCompletionMessage.Role.ASSISTANT, null, null, List.of(tc2), null, null);
        var chunk2 = new ChatCompletionChunk("id1", List.of(new ChatCompletionChunk.ChunkChoice(null, 0, msg2, null)), 1L, "m", null, null, null, null);

        var merged = helper.merge(chunk1, chunk2);
        System.out.println("=== Merged count: " + merged.choices().get(0).delta().toolCalls().size());
        for (var tc : merged.choices().get(0).delta().toolCalls()) {
            System.out.println("  -> id=[" + tc.id() + "], name=[" + tc.function().name() + "], args=[" + tc.function().arguments() + "]");
        }
    }

    @Test
    public void testLiveStreamWithFunctions() {
        System.out.println("Starting testLiveStreamWithFunctions...");
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
            if (sb.length() == 0) {
                throw new IllegalStateException("Stream returned empty response!");
            }
        } catch (Exception e) {
            System.err.println("\n[CAUGHT EXCEPTION IN LIVE TEST]: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
