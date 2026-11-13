package com.research.controller;

import com.research.service.ResearchLoop;
import com.research.service.TaskStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final ResearchLoop loop;
    private final TaskStore store;

    public ResearchController(ResearchLoop loop, TaskStore store) {
        this.loop = loop;
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody Map<String, String> body) {
        String topic = body.get("topic");
        if (topic == null || topic.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "topic 不能为空"));
        }
        String taskId = loop.submit(topic.trim());
        return ResponseEntity.ok(Map.of("taskId", taskId));
    }

    @PostMapping("/{taskId}/pause")
    public ResponseEntity<?> pause(@PathVariable String taskId) {
        loop.pause(taskId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<?> resume(@PathVariable String taskId) {
        loop.resume(taskId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String taskId) {
        loop.cancel(taskId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/{taskId}/report/download")
    public ResponseEntity<?> download(@PathVariable String taskId) {
        Map<String, Object> report = store.getReport(taskId);
        if (report == null || report.get("markdown") == null) {
            return ResponseEntity.notFound().build();
        }
        String title = String.valueOf(report.getOrDefault("title", taskId));
        String filename = title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + ".md";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" +
                        java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Type", "text/markdown; charset=UTF-8")
                .body(report.get("markdown"));
    }

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(loop.listTasks());
    }

    @GetMapping("/{taskId}/report")
    public ResponseEntity<?> report(@PathVariable String taskId) {
        Map<String, Object> report = store.getReport(taskId);
        if (report == null) {
            Map<String, Object> task = store.get(taskId);
            if (task == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(Map.of("status", task.get("status"), "markdown", ""));
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats() {
        return ResponseEntity.ok(Map.of(
                "reviewStats", store.reviewStats(),
                "recentIssues", store.recentIssues(20)));
    }
}
