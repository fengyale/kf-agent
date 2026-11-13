package com.decision.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 页面路由（Task 5：Thymeleaf 三页）：
 * /            首页（输入 + 深度选择 + 首访画像引导）
 * /session/{id} 会话页（采集对话流 + SSE 实时进度 + 断线重连恢复）
 * /report/{id}  报告页（简化可行性报告）
 * /behavior     行为数据页（表单录入 + 粘贴导入 + 分桶校准总览，Task 8）
 * 数据交互全部走既有 REST 接口，模板只注入 analysisId 等最小上下文。
 */
@Controller
public class WebController {

    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/session/{analysisId}")
    public String session(@PathVariable String analysisId, Model model) {
        model.addAttribute("analysisId", analysisId);
        return "session";
    }

    @GetMapping("/report/{analysisId}")
    public String report(@PathVariable String analysisId, Model model) {
        model.addAttribute("analysisId", analysisId);
        return "report";
    }

    @GetMapping("/behavior")
    public String behavior() {
        return "behavior";
    }

    @GetMapping("/history")
    public String history() {
        return "history";
    }

    @GetMapping("/stats")
    public String stats() {
        return "stats";
    }
}
