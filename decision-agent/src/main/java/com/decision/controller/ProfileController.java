package com.decision.controller;

import com.decision.personal.ProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像引导接口（Task 3.6 冷启动画像引导）：
 * GET  /api/profile —— 画像内容；profile 为空且未引导过时附带 3~5 题引导（可跳过）
 * POST /api/profile —— 批量提交引导答案 {answers:{k:v}} 或单条 {k,v}；skipped=true 表示跳过引导
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profile;

    public ProfileController(ProfileService profile) {
        this.profile = profile;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, String> entries = profile.all();
        boolean needGuidance = entries.isEmpty() && !profile.guidanceDone();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("empty", entries.isEmpty());
        resp.put("entries", entries);
        resp.put("guidanceDone", profile.guidanceDone());
        resp.put("guidance", needGuidance ? profile.guidanceQuestions() : List.of());
        resp.put("skippable", true);
        return resp;
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        Object answers = body.get("answers");
        int saved = 0;
        if (answers instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                profile.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
                saved++;
            }
        } else if (body.get("k") != null) {
            profile.put(String.valueOf(body.get("k")),
                    body.get("v") == null ? "" : String.valueOf(body.get("v")));
            saved++;
        }
        // 提交过答案或显式跳过 → 引导完成（不再重复弹出）
        if (saved > 0 || Boolean.TRUE.equals(body.get("skipped"))) {
            profile.markGuidanceDone();
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("saved", saved);
        resp.put("empty", profile.isEmpty());
        return resp;
    }
}
