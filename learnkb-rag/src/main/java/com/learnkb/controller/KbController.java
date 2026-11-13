package com.learnkb.controller;

import com.learnkb.service.ChatService;
import com.learnkb.service.IngestService;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class KbController {

    private final IngestService ingestService;
    private final ChatService chatService;

    public KbController(IngestService ingestService, ChatService chatService) {
        this.ingestService = ingestService;
        this.chatService = chatService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest() {
        try {
            return ResponseEntity.ok(ingestService.ingest());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question 不能为空"));
        }
        try {
            return ResponseEntity.ok(chatService.chat(question.trim()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(180_000L);
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            sendEvent(emitter, "error", "question 不能为空");
            emitter.complete();
            return emitter;
        }
        try {
            chatService.chatStream(question.trim(),
                    thinkingToken -> sendEvent(emitter, "thinking", thinkingToken),
                    token -> sendEvent(emitter, "token", token),
                    result -> {
                        sendEvent(emitter, "done", result);
                        emitter.complete();
                    });
        } catch (Exception e) {
            sendEvent(emitter, "error", e.getMessage());
            emitter.complete();
        }
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 客户端断开等情况，静默即可
        }
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question 不能为空"));
        }
        try {
            List<SearchHit> hits = ingestService.search(question.trim()).stream()
                    .map(m -> new SearchHit(
                            m.embedded().metadata().getString("file_name"),
                            m.score(),
                            trim(m.embedded().text())))
                    .toList();
            return ResponseEntity.ok(hits);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "indexed", ingestService.isIndexed(),
                "segmentCount", ingestService.getSegmentCount());
    }

    private String trim(String text) {
        String t = text.strip();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    public record SearchHit(String file, double score, String snippet) {
    }
}
