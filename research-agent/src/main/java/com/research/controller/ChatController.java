package com.research.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.service.ConversationStore;
import com.research.service.LlmClient;
import com.research.service.PromptBuilder;
import com.research.service.ResearchLoop;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话式入口：
 * - 会话 CRUD（左侧列表）
 * - 消息意图路由：调研类消息 → 创建调研任务（消息带 task_id，前端渲染调研卡片）；
 *   普通问题 → LLM 流式作答（SSE token 流，结束后入库）
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    // 调研意图关键词（规则先行，避免每次消息都调 LLM 判断意图浪费 token）
    private static final List<String> RESEARCH_KEYWORDS = List.of(
            "调研", "了解", "分析", "研究一下", "研究下", "对比", "调查", "梳理", "综述", "情报");

    private final ConversationStore conversations;
    private final ResearchLoop loop;
    private final LlmClient llm;
    private final PromptBuilder prompts;
    private final ObjectMapper om = new ObjectMapper();

    private final ExecutorService chatExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "chat-worker");
        t.setDaemon(true);
        return t;
    });

    public ChatController(ConversationStore conversations, ResearchLoop loop,
                          LlmClient llm, PromptBuilder prompts) {
        this.conversations = conversations;
        this.loop = loop;
        this.llm = llm;
        this.prompts = prompts;
    }

    @GetMapping("/conversations")
    public ResponseEntity<?> listConversations() {
        return ResponseEntity.ok(conversations.list());
    }

    @PostMapping("/conversations")
    public ResponseEntity<?> createConversation(@RequestBody(required = false) Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        String id = conversations.create(title);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<?> deleteConversation(@PathVariable String id) {
        conversations.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<?> messages(@PathVariable String id) {
        if (conversations.get(id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(conversations.messages(id));
    }

    /**
     * 发消息（SSE 流）：
     * event:intent {type} → event:task {taskId,topic}（调研意图）或 token 流（普通问答）→ event:done
     */
    @PostMapping(value = "/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String id, @RequestBody Map<String, String> body) {
        String content = body == null ? "" : String.valueOf(body.get("content"));
        SseEmitter emitter = new SseEmitter(300_000L);

        if (conversations.get(id) == null) {
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"会话不存在\"}"));
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }

        conversations.addMessage(id, "user", content, null);
        conversations.touchTitle(id, content);

        if (isResearchIntent(content)) {
            send(emitter, "intent", Map.of("type", "research"));
            String taskId = loop.submit(content.strip());
            conversations.addMessage(id, "assistant",
                    "已启动调研任务「" + truncate(content.strip(), 40) + "」，全程可监控，完成后自动生成报告。", taskId);
            send(emitter, "task", Map.of("taskId", taskId, "topic", content.strip()));
            send(emitter, "done", Map.of());
            emitter.complete();
        } else {
            send(emitter, "intent", Map.of("type", "chat"));
            chatExecutor.submit(() -> {
                StringBuilder sb = new StringBuilder();
                try {
                    llm.streamText(prompts.chatSystem(), content,
                                    null,
                                    token -> {
                                        sb.append(token);
                                        send(emitter, "token", Map.of("text", token));
                                    })
                            .get(5, java.util.concurrent.TimeUnit.MINUTES);
                    conversations.addMessage(id, "assistant", sb.toString(), null);
                    send(emitter, "done", Map.of());
                } catch (Exception e) {
                    if (sb.length() > 0) conversations.addMessage(id, "assistant", sb.toString(), null);
                    send(emitter, "error", Map.of("message", String.valueOf(e.getMessage())));
                } finally {
                    emitter.complete();
                }
            });
        }
        return emitter;
    }

    private boolean isResearchIntent(String content) {
        String c = content == null ? "" : content.strip();
        if (c.length() < 8) return false; // 太短按闲聊处理
        return RESEARCH_KEYWORDS.stream().anyMatch(c::contains);
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(
                    om.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (Exception ignored) {
        }
    }

    private String truncate(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
