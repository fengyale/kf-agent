package com.research.controller;

import com.research.service.EventPublisher;
import com.research.service.TaskStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final EventPublisher events;
    private final TaskStore store;

    public MonitorController(EventPublisher events, TaskStore store) {
        this.events = events;
        this.store = store;
    }

    @GetMapping(value = "/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter monitor(@PathVariable String taskId) {
        SseEmitter emitter = events.subscribe(taskId);
        String status = store.isFinished(taskId);
        if (status != null) {
            events.complete(taskId);
        }
        return emitter;
    }
}
