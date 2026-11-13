package com.research.service;

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

import com.fasterxml.jackson.databind.ObjectMapper;

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

    public void publish(String taskId, String type, Object payload) {
        String json;
        try {
            json = om.writeValueAsString(payload);
        } catch (Exception e) {
            json = "{}";
        }
        jt.update("INSERT INTO event(task_id, seq, type, payload, created_at) VALUES (?, (SELECT COALESCE(MAX(seq),0)+1 FROM event WHERE task_id=?), ?, ?, ?)",
                taskId, taskId, type, json, LocalDateTime.now().format(TS));
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                send(emitter, type, payload);
            }
        }
    }

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable remove = () -> emitters.getOrDefault(taskId, new CopyOnWriteArrayList<>()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        jt.query("SELECT type, payload FROM event WHERE task_id = ? ORDER BY seq",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        sendRaw(emitter, rs.getString(1), rs.getString(2)), taskId);
        send(emitter, "hello", Map.of("taskId", taskId));
        return emitter;
    }

    public void complete(String taskId) {
        List<SseEmitter> list = emitters.remove(taskId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public boolean hasSubscriber(String taskId) {
        List<SseEmitter> list = emitters.get(taskId);
        return list != null && !list.isEmpty();
    }

    private void heartbeat() {
        emitters.forEach((taskId, list) -> {
            for (SseEmitter emitter : list) {
                send(emitter, "heartbeat", Map.of("ts", System.currentTimeMillis()));
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
