package com.decision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 照搬 research-agent 的 SSE 事件总线三机制：
 * 1. publish() 先落 SQLite 再推在线 emitter（双写 = 天然断线重放）
 * 2. subscribe() 先回放历史事件再挂监听（会话跨天恢复）
 * 3. 10 秒心跳防连接超时
 * 差异仅在于事件表列名 task_id → analysis_id（决策 Agent 的订阅粒度是分析）。
 */
@Service
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jt;
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public EventPublisher(JdbcTemplate jt) {
        this.jt = jt;
        ScheduledExecutorService hb = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
        hb.scheduleAtFixedRate(this::heartbeat, 10, 10, TimeUnit.SECONDS);
    }

    public void publish(String analysisId, String type, Object payload) {
        String json;
        try {
            json = om.writeValueAsString(payload);
        } catch (Exception e) {
            json = "{}";
        }
        jt.update("INSERT INTO event(analysis_id, seq, type, payload, created_at) VALUES (?, (SELECT COALESCE(MAX(seq),0)+1 FROM event WHERE analysis_id=?), ?, ?, ?)",
                analysisId, analysisId, type, json, LocalDateTime.now().format(TS));
        List<SseEmitter> list = emitters.get(analysisId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                send(emitter, type, payload);
            }
        }
    }

    public SseEmitter subscribe(String analysisId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(analysisId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> emitters.getOrDefault(analysisId, new CopyOnWriteArrayList<>()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        jt.query("SELECT type, payload FROM event WHERE analysis_id = ? ORDER BY seq",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        sendRaw(emitter, rs.getString(1), rs.getString(2)), analysisId);
        send(emitter, EventTypes.HELLO, Map.of("analysisId", analysisId));
        return emitter;
    }

    public void complete(String analysisId) {
        List<SseEmitter> list = emitters.remove(analysisId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean hasSubscriber(String analysisId) {
        List<SseEmitter> list = emitters.get(analysisId);
        return list != null && !list.isEmpty();
    }

    private void heartbeat() {
        emitters.forEach((analysisId, list) -> {
            for (SseEmitter emitter : list) {
                send(emitter, EventTypes.HEARTBEAT, Map.of("ts", System.currentTimeMillis()));
            }
        });
    }

    private void send(SseEmitter emitter, String type, Object payload) {
        try {
            String json = om.writeValueAsString(payload);
            sendRaw(emitter, type, json);
        } catch (Exception e) {
            log.debug("SSE 发送失败: {}", e.getMessage());
        }
    }

    private void sendRaw(SseEmitter emitter, String type, String json) {
        try {
            emitter.send(SseEmitter.event().name(type).data(json, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 发送失败: {}", e.getMessage());
        }
    }
}
