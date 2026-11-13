package com.decision.controller;

import com.decision.collector.CollectDesignService;
import com.decision.collector.CollectorStateMachine;
import com.decision.collector.IntentService;
import com.decision.loop.AnalysisStateStore;
import com.decision.loop.AnalysisStore;
import com.decision.loop.BudgetExceededException;
import com.decision.loop.FeasibilityLoop;
import com.decision.loop.FlowRouter;
import com.decision.model.CollectorModels.CollectDesign;
import com.decision.model.CollectorModels.IntentResult;
import com.decision.model.LoopModels.PendingQuestions;
import com.decision.model.LoopModels.QuestionItem;
import com.decision.service.EventPublisher;
import com.decision.service.EventTypes;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 采集会话接口（线1，Task 3.5）：
 * POST /api/chat/start   —— 提交问题 → 意图识别（模板1）→ 创建 analysis 记录 → 生成采集问题（模板2）
 * POST /api/chat/answer  —— 回答/跳过/直接给结论 → 填槽 → 槽位满或降级 → 触发/恢复分析
 * GET  /api/events/{id}  —— SSE 订阅（复用 EventPublisher：先回放历史再挂监听 + 心跳，跨天可恢复）
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IntentService intentService;
    private final CollectDesignService collectDesignService;
    private final CollectorStateMachine collector;
    private final AnalysisStore analysisStore;
    private final AnalysisStateStore stateStore;
    private final FeasibilityLoop loop;
    private final EventPublisher events;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ChatController(IntentService intentService, CollectDesignService collectDesignService,
                          CollectorStateMachine collector, AnalysisStore analysisStore,
                          AnalysisStateStore stateStore, FeasibilityLoop loop,
                          EventPublisher events) {
        this.intentService = intentService;
        this.collectDesignService = collectDesignService;
        this.collector = collector;
        this.analysisStore = analysisStore;
        this.stateStore = stateStore;
        this.loop = loop;
        this.events = events;
    }

    // ==================== 开始会话 ====================

    @PostMapping("/api/chat/start")
    public Map<String, Object> start(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "") == null ? "" : body.getOrDefault("question", "").strip();
        if (question.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        String depthOverride = body.get("depth");
        String analysisId = UUID.randomUUID().toString().substring(0, 12);
        // 先建 analysis 记录：意图识别/采集设计的调用都要计入该分析的模型调用预算（trace）
        analysisStore.create(analysisId, question, null, null, "waiting");
        try {
            // 意图识别（模板1）
            IntentResult intent = intentService.recognize(analysisId, question);
            String type = IntentService.normalizeType(intent.type());
            String override = FlowRouter.normalizeDepth(depthOverride);
            String depth = override != null ? override : IntentService.defaultDepth(type, intent.depth());
            analysisStore.updateIntent(analysisId, type, depth);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("analysisId", analysisId);
            resp.put("question", question);
            resp.put("intent", intentView(intent, type, depth));
            resp.put("eventsUrl", "/api/events/" + analysisId);

            LinkedHashMap<String, Object> steps = new LinkedHashMap<>();
            steps.put("intent", intent);

            if ("light".equals(depth)) {
                // 轻量路径（日常事务）：跳过采集，意图 + 简短评估 1~2 次调用出结论
                stateStore.create(analysisId, "assess-light", toJson(steps), "{}",
                        AnalysisStateStore.STATUS_WAITING, null);
                loop.run(analysisId);
                resp.put("triggered", true);
                resp.put("status", "running");
                return resp;
            }

            // 采集设计（模板2）+ 首批追问（最多 3 问，仅关键未填槽位）
            CollectDesign design = collectDesignService.design(analysisId, type, question);
            steps.put("collect", design);
            List<QuestionItem> questions = collector.start(analysisId, design);

            if (questions.isEmpty() || collector.isSatisfied(analysisId)) {
                // 关键槽位无缺口（或可全部默认）→ 直接进分析
                stateStore.create(analysisId, "assess", toJson(steps), collector.snapshotJson(analysisId),
                        AnalysisStateStore.STATUS_WAITING, null);
                loop.run(analysisId);
                resp.put("triggered", true);
                resp.put("status", "running");
                return resp;
            }

            String pending = toJson(new PendingQuestions("collect", questions, LocalDateTime.now().format(TS)));
            stateStore.create(analysisId, "collect", toJson(steps), collector.snapshotJson(analysisId),
                    AnalysisStateStore.STATUS_WAITING, pending);
            events.publish(analysisId, EventTypes.COLLECT_QUESTION, questionPayload(questions));
            log.info("[{}] 会话启动: type={} depth={} 追问 {} 项", analysisId, type, depth, questions.size());
            resp.put("triggered", false);
            resp.put("status", "waiting");
            resp.put("questions", questions);
            resp.put("skippable", true);
            return resp;
        } catch (Exception e) {
            return errorOut(analysisId, e);
        }
    }

    // ==================== 回答 / 跳过 / 直接给结论 ====================

    @PostMapping("/api/chat/answer")
    public Map<String, Object> answer(@RequestBody Map<String, Object> body) {
        String analysisId = str(body.get("analysisId"));
        String action = body.get("action") == null ? "answer" : str(body.get("action"));
        if (analysisId == null || analysisId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "analysisId 不能为空");
        }
        Map<String, Object> analysis = analysisStore.get(analysisId);
        if (analysis == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分析不存在: " + analysisId);
        }
        AnalysisStateStore.AnalysisState state = stateStore.get(analysisId);
        if (state != null && isTerminal(state.status())) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("analysisId", analysisId);
            resp.put("status", state.status());
            resp.put("message", "分析已结束（" + state.status() + "），可查看报告");
            resp.put("reportUrl", "/api/analysis/" + analysisId + "/report");
            return resp;
        }

        switch (action) {
            case "skip" -> collector.skip(analysisId);
            case "conclude" -> collector.forceConclude(analysisId);
            default -> {
                String slotName = str(body.get("slotName"));
                String value = str(body.get("value"));
                if (slotName == null || slotName.isBlank()) {
                    slotName = firstPendingSlot(state); // 未指定槽位时按当前待答问题
                }
                if (value == null || value.isBlank() || slotName == null || slotName.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "回答需要 value + slotName（或存在待答问题）");
                }
                collector.fillSlot(analysisId, slotName, value);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysisId", analysisId);
        // 槽位满或降级 → 触发分析（waiting 挂起的从断点恢复）
        if (collector.isSatisfied(analysisId)) {
            stateStore.updatePending(analysisId, null, AnalysisStateStore.STATUS_WAITING);
            loop.run(analysisId);
            resp.put("triggered", true);
            resp.put("status", "running");
            return resp;
        }
        List<QuestionItem> next = collector.nextQuestions(analysisId);
        if (next.isEmpty()) {
            // 兜底：无关键缺口即触发
            stateStore.updatePending(analysisId, null, AnalysisStateStore.STATUS_WAITING);
            loop.run(analysisId);
            resp.put("triggered", true);
            resp.put("status", "running");
            return resp;
        }
        String pending = toJson(new PendingQuestions("collect", next, LocalDateTime.now().format(TS)));
        stateStore.updatePending(analysisId, pending, AnalysisStateStore.STATUS_WAITING);
        events.publish(analysisId, EventTypes.COLLECT_QUESTION, questionPayload(next));
        resp.put("triggered", false);
        resp.put("status", "waiting");
        resp.put("questions", next);
        resp.put("skippable", true);
        return resp;
    }

    // ==================== SSE 订阅（复用 EventPublisher） ====================

    @GetMapping(path = "/api/events/{analysisId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String analysisId) {
        return events.subscribe(analysisId);
    }

    // ==================== 内部 ====================

    private static boolean isTerminal(String status) {
        return AnalysisStateStore.STATUS_DONE.equals(status)
                || AnalysisStateStore.STATUS_ERROR.equals(status)
                || AnalysisStateStore.STATUS_CANCELLED.equals(status);
    }

    private String firstPendingSlot(AnalysisStateStore.AnalysisState state) {
        if (state == null || state.pendingQuestion() == null || state.pendingQuestion().isBlank()) {
            return null;
        }
        try {
            PendingQuestions pq = om.readValue(state.pendingQuestion(), PendingQuestions.class);
            if (pq.questions() != null && !pq.questions().isEmpty()) {
                return pq.questions().get(0).slot();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Map<String, Object> intentView(IntentResult intent, String type, String depth) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("goal", intent.goal());
        view.put("pathHint", intent.pathHint());
        view.put("type", type);
        view.put("urgency", intent.urgency());
        view.put("depth", depth);
        return view;
    }

    private Map<String, Object> questionPayload(List<QuestionItem> questions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("questions", questions);
        payload.put("skippable", true);
        return payload;
    }

    /** 会话启动阶段失败：分析标记 error + 发 error 事件（EventPublisher 双写可回放） */
    private Map<String, Object> errorOut(String analysisId, Exception e) {
        log.warn("[{}] 会话启动失败: {}", analysisId, e.getMessage());
        analysisStore.finish(analysisId, "error", null, 0);
        stateStore.updateStatus(analysisId, AnalysisStateStore.STATUS_ERROR);
        events.publish(analysisId, EventTypes.ERROR,
                Map.of("message", String.valueOf(e.getMessage()),
                        "exitReason", e instanceof BudgetExceededException ? "guard:budget" : "error:start"));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("analysisId", analysisId);
        resp.put("status", "error");
        resp.put("error", String.valueOf(e.getMessage()));
        return resp;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
