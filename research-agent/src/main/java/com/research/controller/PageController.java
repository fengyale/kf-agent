package com.research.controller;

import com.research.service.TaskStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

@Controller
public class PageController {

    private final TaskStore store;

    public PageController(TaskStore store) {
        this.store = store;
    }

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/tasks")
    public String tasks() {
        return "tasks";
    }

    /** 无 taskId 直接访问监控页：自动定位最近任务（运行中优先，否则最新一条） */
    @GetMapping("/monitor")
    public String monitorLatest(Model model) {
        String taskId = "";
        List<Map<String, Object>> list = store.listAll();
        if (!list.isEmpty()) {
            Map<String, Object> running = list.stream()
                    .filter(t -> "running".equals(t.get("status")))
                    .findFirst().orElse(null);
            Map<String, Object> target = running != null ? running : list.get(0);
            taskId = String.valueOf(target.get("id"));
        }
        model.addAttribute("taskId", taskId);
        return "monitor";
    }

    @GetMapping("/monitor/{taskId}")
    public String monitor(@PathVariable String taskId, Model model) {
        model.addAttribute("taskId", taskId);
        return "monitor";
    }
}
