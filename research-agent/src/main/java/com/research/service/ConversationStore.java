package com.research.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话式首页的数据层：conversation / message 两张表。
 * 会话承载聊天上下文；其中识别为调研意图的用户消息会关联 task_id，
 * 前端据此渲染可跳转监控页的调研卡片。
 */
@Service
public class ConversationStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jt;

    public ConversationStore(JdbcTemplate jt) {
        this.jt = jt;
    }

    public String create(String title) {
        String id = UUID.randomUUID().toString().substring(0, 12);
        String now = LocalDateTime.now().format(TS);
        String t = title == null || title.isBlank() ? "新对话" : title.strip();
        if (t.length() > 30) t = t.substring(0, 30);
        jt.update("INSERT INTO conversation(id, title, created_at, updated_at) VALUES (?, ?, ?, ?)",
                id, t, now, now);
        return id;
    }

    public List<Map<String, Object>> list() {
        return jt.queryForList(
                "SELECT c.id, c.title, c.updated_at, " +
                        "(SELECT COUNT(*) FROM message m WHERE m.conversation_id = c.id) AS message_count " +
                        "FROM conversation c ORDER BY c.updated_at DESC, c.id DESC LIMIT 100");
    }

    public Map<String, Object> get(String id) {
        List<Map<String, Object>> rows = jt.queryForList("SELECT id, title, created_at, updated_at FROM conversation WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void addMessage(String conversationId, String role, String content, String taskId) {
        String now = LocalDateTime.now().format(TS);
        jt.update("INSERT INTO message(conversation_id, role, content, task_id, created_at) VALUES (?, ?, ?, ?, ?)",
                conversationId, role, content, taskId, now);
        jt.update("UPDATE conversation SET updated_at=? WHERE id=?", now, conversationId);
    }

    public List<Map<String, Object>> messages(String conversationId) {
        return jt.queryForList(
                "SELECT id, role, content, task_id, created_at FROM message WHERE conversation_id=? ORDER BY id ASC",
                conversationId);
    }

    /** 用首条用户消息初始化会话标题（仅当标题仍是占位"新对话"时） */
    public void touchTitle(String conversationId, String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) return;
        String t = firstUserMessage.strip();
        if (t.length() > 30) t = t.substring(0, 30);
        jt.update("UPDATE conversation SET title=? WHERE id=? AND (title IS NULL OR title='' OR title='新对话')",
                t, conversationId);
    }

    public void delete(String conversationId) {
        jt.update("DELETE FROM message WHERE conversation_id=?", conversationId);
        jt.update("DELETE FROM conversation WHERE id=?", conversationId);
    }
}
