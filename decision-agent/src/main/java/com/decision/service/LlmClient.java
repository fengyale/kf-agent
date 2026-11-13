package com.decision.service;

import com.decision.config.AgentProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 照搬 research-agent 的 generateJson（JSON 提取 + 解析失败重试一次）与
 * streamText（onPartialThinking + onToken 双回调，中断时保留已生成部分），
 * 新增测试录放：record-mode 真实调用存请求/响应文件（按调用序号命名）；
 * replay-mode 按序号读录制文件直接回放，不真调模型。
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel;
    private final AgentProperties props;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    // 进程内调用序号：录制与回放共用，保证回放时与录制顺序一一对应
    private final AtomicLong callSeq = new AtomicLong(0);

    public LlmClient(ChatModel chatModel, StreamingChatModel streamingModel, AgentProperties props) {
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
        this.props = props;
    }

    public String modelName() {
        return props.getLlm().activeModel();
    }

    public <T> T generateJson(String system, String userPrompt, Class<T> type) {
        return generateJson(system, userPrompt, type, null);
    }

    /**
     * 结构化调用。"解析失败重试一次"前的回调，供调用方把重试也计入预算与 trace
     * （重试同样产生费用）：回调抛异常则不再重试，直接抛出。
     */
    public <T> T generateJson(String system, String userPrompt, Class<T> type, Runnable beforeRetry) {
        String content = chat(system, userPrompt);
        try {
            return om.readValue(extractJson(content), type);
        } catch (Exception e) {
            log.info("JSON 解析失败，重试一次: {}", e.getMessage());
        }
        if (beforeRetry != null) {
            beforeRetry.run();
        }
        String retry = chat(system, "你上一次的输出无法解析为 JSON。严格只输出合法 JSON，不要任何其他文字。\n\n" + userPrompt);
        try {
            return om.readValue(extractJson(retry), type);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 结构化输出解析失败（已重试）: " + e.getMessage(), e);
        }
    }

    /**
     * 流式 JSON 生成（解决"页面干等"体验问题，spec 分析:thinking 事件的后端实现）：
     * 用 streamingModel 走 returnThinking 流，思考片段经节流实时回调 onThinking（调用方推 SSE），
     * 正文全文收集完成后提取/解析 JSON（解析失败重试一次，与 generateJson 同款兜底）。
     * replay 模式直接回放录制文件（无 thinking，仅返回正文）。
     */
    public <T> T generateJsonStreaming(String system, String userPrompt, Class<T> type,
                                       Consumer<String> onThinking) {
        return generateJsonStreaming(system, userPrompt, type, onThinking, null);
    }

    /** 流式结构化调用（重试语义同 generateJson，beforeRetry 让重试计入预算/trace） */
    public <T> T generateJsonStreaming(String system, String userPrompt, Class<T> type,
                                       Consumer<String> onThinking, Runnable beforeRetry) {
        String content = chatStreaming(system, userPrompt, onThinking);
        try {
            return om.readValue(extractJson(content), type);
        } catch (Exception e) {
            log.info("JSON 解析失败，流式重试一次: {}", e.getMessage());
        }
        if (beforeRetry != null) {
            beforeRetry.run();
        }
        String retry = chatStreaming(system,
                "你上一次的输出无法解析为 JSON。严格只输出合法 JSON，不要任何其他文字。\n\n" + userPrompt,
                onThinking);
        try {
            return om.readValue(extractJson(retry), type);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 结构化输出解析失败（已重试）: " + e.getMessage(), e);
        }
    }

    /** 流式生成完整正文（阻塞等待完成）；thinking 片段节流后实时回调（≥120 字符 flush 一次） */
    private String chatStreaming(String system, String userPrompt, Consumer<String> onThinking) {
        if (props.getLlm().isReplayMode()) {
            return replayNext();
        }
        try {
            return streamText(system, userPrompt, throttled(onThinking), t -> { }).join();
        } catch (Exception e) {
            Throwable c = e instanceof java.util.concurrent.CompletionException && e.getCause() != null
                    ? e.getCause() : e;
            throw new IllegalStateException("流式生成失败: " + c.getMessage(), c);
        }
    }

    /** thinking 节流：片段累积到阈值才回调，避免逐段刷 SSE 事件 */
    private static Consumer<String> throttled(Consumer<String> onThinking) {
        if (onThinking == null) {
            return null;
        }
        StringBuilder buf = new StringBuilder();
        return seg -> {
            synchronized (buf) {
                buf.append(seg);
                if (buf.length() >= 120) {
                    String flush = buf.toString();
                    buf.setLength(0);
                    onThinking.accept(flush);
                }
            }
        };
    }

    public CompletableFuture<String> streamText(String system, String userPrompt,
                                                Consumer<String> onThinking, Consumer<String> onToken) {
        if (props.getLlm().isReplayMode()) {
            String recorded = replayNext();
            if (onToken != null) onToken.accept(recorded);
            return CompletableFuture.completedFuture(recorded);
        }
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
                        record(system, userPrompt, sb.toString());
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
        if (props.getLlm().isReplayMode()) {
            return replayNext();
        }
        long start = System.currentTimeMillis();
        var response = chatModel.chat(List.of(SystemMessage.from(system), UserMessage.from(userPrompt)));
        String content = response.aiMessage().text();
        if (content == null) content = "";
        record(system, userPrompt, content);
        log.info("LLM 调用完成: 输出{}字 耗时{}ms", content.length(), System.currentTimeMillis() - start);
        return content;
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("输出中不含 JSON 对象");
        }
        return content.substring(start, end + 1);
    }

    // ==================== 测试录放 ====================

    private void record(String system, String userPrompt, String response) {
        if (!props.getLlm().isRecordMode()) {
            return;
        }
        try {
            long seq = callSeq.incrementAndGet();
            Path dir = Path.of(props.getLlm().getRecordingsDir());
            Files.createDirectories(dir);
            ObjectNode node = om.createObjectNode();
            node.put("seq", seq);
            node.put("model", props.getLlm().activeModel());
            node.put("system", system);
            node.put("userPrompt", userPrompt);
            node.put("response", response);
            Files.writeString(recordingFile(seq), om.writeValueAsString(node));
            log.info("LLM 调用已录制: {}", recordingFile(seq));
        } catch (IOException e) {
            // 录制失败不影响主流程
            log.warn("LLM 调用录制失败: {}", e.getMessage());
        }
    }

    private String replayNext() {
        long seq = callSeq.incrementAndGet();
        Path file = recordingFile(seq);
        if (!Files.exists(file)) {
            throw new IllegalStateException("回放模式下缺少录制文件: " + file
                    + "（请先开启 agent.llm.record-mode 真实运行一轮生成录制）");
        }
        try {
            String response = om.readTree(Files.readString(file)).path("response").asText("");
            log.info("LLM 回放调用: {} 响应{}字", file.getFileName(), response.length());
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("LLM 回放读取录制文件失败: " + file, e);
        }
    }

    private Path recordingFile(long seq) {
        return Path.of(props.getLlm().getRecordingsDir(), String.format("%04d.json", seq));
    }
}
