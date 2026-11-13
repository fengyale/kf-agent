package com.research.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.config.AgentProperties;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel;
    private final AgentProperties props;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LlmClient(ChatModel chatModel, StreamingChatModel streamingModel, AgentProperties props) {
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
        this.props = props;
    }

    public String modelName() {
        return props.getLlm().activeModel();
    }

    public <T> T generateJson(String system, String userPrompt, Class<T> type) {
        String content = chat(system, userPrompt);
        try {
            return om.readValue(extractJson(content), type);
        } catch (Exception e) {
            log.info("JSON 解析失败，重试一次: {}", e.getMessage());
        }
        String retry = chat(system, "你上一次的输出无法解析为 JSON。严格只输出合法 JSON，不要任何其他文字。\n\n" + userPrompt);
        try {
            return om.readValue(extractJson(retry), type);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 结构化输出解析失败（已重试）: " + e.getMessage(), e);
        }
    }

    public CompletableFuture<String> streamText(String system, String userPrompt,
                                                Consumer<String> onThinking, Consumer<String> onToken) {
        CompletableFuture<String> future = new CompletableFuture<>();
        long start = System.currentTimeMillis();
        streamingModel.chat(List.of(SystemMessage.from(system), UserMessage.from(userPrompt)),
                new StreamingChatResponseHandler() {
                    final StringBuilder sb = new StringBuilder();
                    final StringBuilder thinking = new StringBuilder();

                    @Override
                    public void onPartialResponse(String token) {
                        sb.append(token);
                        onToken.accept(token);
                    }

                    @Override
                    public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                        thinking.append(partialThinking.text());
                        if (onThinking != null) onThinking.accept(partialThinking.text());
                    }

                    @Override
                    public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                        log.info("流式生成完成: 思考{}字 输出{}字 耗时{}ms",
                                thinking.length(), sb.length(), System.currentTimeMillis() - start);
                        future.complete(sb.toString());
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.warn("流式生成失败: {}", error.getMessage());
                        if (sb.length() > 0) {
                            future.complete(sb + "\n\n（⚠ 生成中断：" + error.getMessage() + "）");
                        } else {
                            future.completeExceptionally(new IllegalStateException("生成失败: " + error.getMessage(), error));
                        }
                    }
                });
        return future;
    }

    private String chat(String system, String userPrompt) {
        long start = System.currentTimeMillis();
        var response = chatModel.chat(List.of(SystemMessage.from(system), UserMessage.from(userPrompt)));
        String content = response.aiMessage().text();
        log.info("LLM 调用完成: 输出{}字 耗时{}ms", content == null ? 0 : content.length(), System.currentTimeMillis() - start);
        return content == null ? "" : content;
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("输出中不含 JSON 对象");
        }
        return content.substring(start, end + 1);
    }
}
