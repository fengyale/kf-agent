package com.research.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class TaskStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jt;

    public TaskStore(JdbcTemplate jt) {
        this.jt = jt;
    }

    public void create(String id, String topic) {
        jt.update("INSERT INTO task(id, topic, status, rounds, search_count, created_at) VALUES (?, ?, 'running', 0, 0, ?)",
                id, topic, LocalDateTime.now().format(TS));
    }

    public void finish(String id, String status, String reportPath, int rounds, int searchCount, long totalCostMs) {
        jt.update("UPDATE task SET status=?, report_path=?, rounds=?, search_count=?, total_cost_ms=?, finished_at=? WHERE id=?",
                status, reportPath, rounds, searchCount, totalCostMs, LocalDateTime.now().format(TS), id);
    }

    public void updateProgress(String id, int rounds, int searchCount) {
        jt.update("UPDATE task SET rounds=?, search_count=? WHERE id=?", rounds, searchCount, id);
    }

    public Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = jt.queryForList("SELECT * FROM task WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listAll() {
        return jt.queryForList("SELECT id, topic, status, report_path, rounds, search_count, total_cost_ms, created_at, finished_at FROM task ORDER BY created_at DESC, id DESC LIMIT 100");
    }

    public Map<String, Object> getReport(String taskId) {
        List<Map<String, Object>> rows = jt.queryForList("SELECT title, markdown, created_at FROM report WHERE task_id=? ORDER BY id DESC LIMIT 1", taskId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveReport(String taskId, String title, String markdown) {
        jt.update("INSERT INTO report(task_id, title, markdown, created_at) VALUES (?, ?, ?, ?)",
                taskId, title, markdown, LocalDateTime.now().format(TS));
    }

    public void saveReviewIssue(String taskId, String type, String description, String section) {
        jt.update("INSERT INTO review_issues(task_id, type, description, section, created_at) VALUES (?, ?, ?, ?, ?)",
                taskId, type, description, section, LocalDateTime.now().format(TS));
    }

    public List<Map<String, Object>> reviewStats() {
        return jt.queryForList("SELECT type, COUNT(*) AS cnt FROM review_issues GROUP BY type ORDER BY cnt DESC");
    }

    public List<Map<String, Object>> recentIssues(int limit) {
        return jt.queryForList("SELECT task_id, type, description, section, created_at FROM review_issues ORDER BY id DESC LIMIT ?", limit);
    }

    public String isFinished(String taskId) {
        List<String> rows = jt.query("SELECT status FROM task WHERE id=?", (rs, i) -> rs.getString(1), taskId);
        if (rows.isEmpty()) return null;
        String status = rows.get(0);
        return "running".equals(status) ? null : status;
    }
}
