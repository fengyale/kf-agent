package com.decision.controller;

import com.decision.personal.BehaviorLogService;
import com.decision.personal.BehaviorLogService.BehaviorRecord;
import com.decision.personal.BehaviorLogService.ExtractedBehavior;
import com.decision.personal.CalibrationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行为数据接口（Task 8 双入口）：
 * GET    /api/behavior            —— 记录列表（可按桶过滤）+ 四桶校准总览
 * POST   /api/behavior            —— 手填表单录入
 * POST   /api/behavior/extract    —— 粘贴文本 LLM 提取（预览，不入库）
 * POST   /api/behavior/import     —— 提取结果确认入库
 * DELETE /api/behavior/{id}       —— 删除记录
 */
@RestController
@RequestMapping("/api/behavior")
public class BehaviorController {

    private final BehaviorLogService behavior;
    private final CalibrationService calibration;

    public BehaviorController(BehaviorLogService behavior, CalibrationService calibration) {
        this.behavior = behavior;
        this.calibration = calibration;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String type) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("types", CalibrationService.BUCKETS);
        resp.put("records", behavior.list(type, 200));
        resp.put("calibrations", calibration.allBuckets());
        return resp;
    }

    /** 表单录入：{type, title, plannedValue, actualValue, completed, occurredAt, isSelfEval} */
    @PostMapping
    public Map<String, Object> record(@RequestBody Map<String, Object> body) {
        String type = str(body.get("type"));
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type 不能为空（日常事务/学习计划/远期规划/商业经营）");
        }
        BehaviorRecord saved = behavior.record(type,
                str(body.get("title")),
                num(body.get("plannedValue")),
                num(body.get("actualValue")),
                Boolean.TRUE.equals(body.get("completed")) || "true".equals(str(body.get("completed"))),
                str(body.get("occurredAt")),
                Boolean.TRUE.equals(body.get("isSelfEval")));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("saved", saved);
        resp.put("calibration", calibration.getCalibration(type));
        return resp;
    }

    /** 粘贴文本提取预览（不入库，等确认） */
    @PostMapping("/extract")
    public Map<String, Object> extract(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        List<ExtractedBehavior> records = behavior.extractFromText(text);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("records", records);
        resp.put("count", records.size());
        resp.put("note", "预览结果，确认后才会入库");
        return resp;
    }

    /** 确认导入：{type, records:[提取结果（可含用户修改）]} */
    @PostMapping("/import")
    public Map<String, Object> confirmImport(@RequestBody Map<String, Object> body) {
        String type = str(body.get("type"));
        if (type == null || type.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type 不能为空（导入到哪个桶）");
        }
        List<ExtractedBehavior> records = parseRecords(body.get("records"));
        int imported = behavior.confirmImport(type, records);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("imported", imported);
        resp.put("calibration", calibration.getCalibration(type));
        return resp;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        if (!behavior.delete(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在: " + id);
        }
        return Map.of("deleted", true, "id", id);
    }

    // ==================== 内部 ====================

    private static List<ExtractedBehavior> parseRecords(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(r -> r instanceof Map)
                .map(r -> {
                    Map<?, ?> m = (Map<?, ?>) r;
                    return new ExtractedBehavior(num(m.get("plannedValue")), num(m.get("actualValue")),
                            str(m.get("completed")), str(m.get("occurredAt")), str(m.get("note")));
                })
                .toList();
    }

    private static Double num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            String s = String.valueOf(o).strip();
            return s.isEmpty() ? null : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
