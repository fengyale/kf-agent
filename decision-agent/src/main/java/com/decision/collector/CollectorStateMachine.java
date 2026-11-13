package com.decision.collector;

import com.decision.loop.AnalysisStateStore;
import com.decision.model.CollectorModels.CollectDesign;
import com.decision.model.CollectorModels.CollectField;
import com.decision.model.CollectorModels.SlotRecord;
import com.decision.model.LoopModels.QuestionItem;
import com.decision.service.EventPublisher;
import com.decision.service.EventTypes;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集状态机（线1）：管理上下文槽位的填充与缺失检测。
 *
 * - nextQuestions：仅关键未填槽位，一次最多 3 问
 * - fillSlot：别名归一 + 敏感脱敏 + 入库 + collect:filled 事件
 * - skip：连续拒答/跳过 3 次自动降级（不再追问，次要字段填默认值）
 * - forceConclude：直接给结论（立即降级，关键缺失字段按条件假设进三档分支分析）
 * - 次要字段缺失填默认值并标注"基于假设X"（assumed 标记进快照，报告引用）
 *
 * 会话态（设计字段/默认值/假设标记/跳过计数/降级标记）持久化在 analysis_state.slot_snapshot，
 * 槽位值本身在 slot 表——两处共同构成可跨天恢复的完整采集现场。
 */
@Service
public class CollectorStateMachine {

    private static final Logger log = LoggerFactory.getLogger(CollectorStateMachine.class);

    /** 一次最多 3 问（spec Requirement: 采集会话） */
    public static final int MAX_QUESTIONS_PER_ROUND = 3;
    /** 连续拒答/跳过 3 次自动降级 */
    public static final int SKIP_DEGRADE_LIMIT = 3;

    /** 采集设计字段会话态（slot_snapshot JSON 的 fields 元素） */
    public record FieldState(String name, String importance, String question,
                             String defaultValue, boolean standard, boolean assumed) {}

    /** 降级元数据（slot_snapshot JSON 的 meta） */
    public record SnapshotMeta(int skipCount, boolean degraded) {}

    /** 采集会话快照（analysis_state.slot_snapshot 的 JSON 形态） */
    public record Snapshot(SnapshotMeta meta, List<FieldState> fields) {}

    private final SlotStore slotStore;
    private final EventPublisher events;
    private final AnalysisStateStore stateStore;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CollectorStateMachine(SlotStore slotStore, EventPublisher events, AnalysisStateStore stateStore) {
        this.slotStore = slotStore;
        this.events = events;
        this.stateStore = stateStore;
    }

    // ==================== 会话启动 ====================

    /** 采集字段落 slot 表 + 快照落 analysis_state，返回首批追问（可能为空 → 无关键缺口） */
    public List<QuestionItem> start(String analysisId, CollectDesign design) {
        List<FieldState> fields = new ArrayList<>();
        if (design != null && design.fields() != null) {
            for (CollectField f : design.fields()) {
                boolean standard = SlotVocabulary.isStandard(f.name());
                fields.add(new FieldState(f.name(), f.importance(), f.question(), f.defaultValue(), standard, false));
                slotStore.upsert(new SlotRecord(0, analysisId, f.name(), f.importance(), null, false, standard));
            }
        }
        persistSnapshot(analysisId, new Snapshot(new SnapshotMeta(0, false), fields));
        return nextQuestions(analysisId);
    }

    // ==================== 追问与填槽 ====================

    /** 下一批追问：仅关键未填槽位（以 slot 表为准，含 ask:followup 补建的关键槽位），最多 3 问 */
    public List<QuestionItem> nextQuestions(String analysisId) {
        Snapshot snap = readSnapshot(analysisId);
        List<QuestionItem> questions = new ArrayList<>();
        for (SlotRecord r : slotStore.list(analysisId)) {
            if (!"key".equals(r.importance()) || r.filled()) {
                continue;
            }
            questions.add(new QuestionItem(r.name(), questionFor(snap, r.name()), true));
            if (questions.size() >= MAX_QUESTIONS_PER_ROUND) {
                break;
            }
        }
        return questions;
    }

    /** 填槽：归一化 + 脱敏 + 入库 + collect:filled 事件（值不进任何提示词前的唯一入口） */
    public SlotRecord fillSlot(String analysisId, String rawName, String value) {
        String name = SlotVocabulary.normalize(rawName);
        String masked = SlotVocabulary.maskSensitive(name, value);
        SlotRecord existing = slotStore.find(analysisId, name)
                .orElseGet(() -> new SlotRecord(0, analysisId, name, "key", null, false, SlotVocabulary.isStandard(name)));
        SlotRecord updated = new SlotRecord(existing.id(), analysisId, name,
                existing.importance(), masked, true, existing.standard());
        slotStore.upsert(updated);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("slot", name);
        payload.put("value", masked == null ? "" : masked);
        events.publish(analysisId, EventTypes.COLLECT_FILLED, payload);
        log.info("[{}] 填槽 {} = {}{}", analysisId, name,
                SlotVocabulary.isMasked(masked) ? "[已脱敏]" : masked,
                SlotVocabulary.isMasked(masked) ? "（敏感值，不进提示词）" : "");
        return updated;
    }

    /** 跳过：连续 3 次自动降级（次要字段填默认值）。返回是否应触发分析（降级即触发） */
    public boolean skip(String analysisId) {
        Snapshot snap = readSnapshot(analysisId);
        int count = snap.meta().skipCount() + 1;
        boolean degraded = snap.meta().degraded() || count >= SKIP_DEGRADE_LIMIT;
        Snapshot updated;
        if (degraded && !snap.meta().degraded()) {
            updated = fillSecondaryDefaults(analysisId, new Snapshot(new SnapshotMeta(count, true), snap.fields()));
        } else {
            updated = new Snapshot(new SnapshotMeta(count, degraded), snap.fields());
        }
        persistSnapshot(analysisId, updated);
        log.info("[{}] 跳过（第 {}/{} 次）degraded={}", analysisId, count, SKIP_DEGRADE_LIMIT, degraded);
        return degraded;
    }

    /** 直接给结论：立即降级——关键缺失字段不追问，按条件假设切三档分支分析 */
    public void forceConclude(String analysisId) {
        Snapshot snap = readSnapshot(analysisId);
        Snapshot degraded = fillSecondaryDefaults(analysisId,
                new Snapshot(new SnapshotMeta(snap.meta().skipCount(), true), snap.fields()));
        persistSnapshot(analysisId, degraded);
        log.info("[{}] 用户选择直接给结论，降级进入分支分析", analysisId);
    }

    // ==================== 状态查询 ====================

    /** 关键槽位全部填充或已降级 → 可进入/恢复分析 */
    public boolean isSatisfied(String analysisId) {
        return readSnapshot(analysisId).meta().degraded() || missingKeySlots(analysisId).isEmpty();
    }

    public boolean isDegraded(String analysisId) {
        return readSnapshot(analysisId).meta().degraded();
    }

    /** 关键未填槽位（contextAdequacy 检查依据） */
    public List<SlotRecord> missingKeySlots(String analysisId) {
        return slotStore.list(analysisId).stream()
                .filter(s -> "key".equals(s.importance()) && !s.filled())
                .toList();
    }

    public SlotRecord slot(String analysisId, String name) {
        return slotStore.find(analysisId, SlotVocabulary.normalize(name)).orElse(null);
    }

    /** ask:followup 挂起时补建关键槽位行（LLM 声明缺失的槽位可能不在采集设计里） */
    public void ensureKeySlots(String analysisId, List<QuestionItem> questions) {
        for (QuestionItem q : questions) {
            String name = SlotVocabulary.normalize(q.slot());
            if (slotStore.find(analysisId, name).isEmpty()) {
                slotStore.upsert(new SlotRecord(0, analysisId, name, "key", null, false, SlotVocabulary.isStandard(name)));
            }
        }
    }

    // ==================== 分析线输入（工作记忆压缩纪律） ====================

    /**
     * 分析线提示词上下文：仅结构化槽位（脱敏值剔除、原始对话文本绝不进入），
     * 附假设清单与降级标记。computed 确定性数字由 FeasibilityLoop 注入。
     */
    public Map<String, Object> promptContext(String analysisId) {
        Snapshot snap = readSnapshot(analysisId);
        Map<String, Object> slots = new LinkedHashMap<>();
        List<String> assumptions = new ArrayList<>();
        for (FieldState f : snap.fields()) {
            SlotRecord r = slotStore.find(analysisId, f.name()).orElse(null);
            if (r == null || !r.filled() || r.value() == null || SlotVocabulary.isMasked(r.value())) {
                continue;
            }
            slots.put(f.name(), r.value());
            if (f.assumed()) {
                assumptions.add(f.name() + "=" + r.value());
            }
        }
        // 快照外已填槽位（ask:followup 补建后用户回答的）
        for (SlotRecord r : slotStore.list(analysisId)) {
            if (!slots.containsKey(r.name()) && r.filled() && r.value() != null && !SlotVocabulary.isMasked(r.value())) {
                slots.put(r.name(), r.value());
            }
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("slots", slots);
        if (!assumptions.isEmpty()) {
            ctx.put("assumptions", assumptions);
        }
        if (snap.meta().degraded()) {
            ctx.put("degraded", true);
        }
        return ctx;
    }

    public List<SlotRecord> listSlots(String analysisId) {
        return slotStore.list(analysisId);
    }

    // ==================== 快照持久化 ====================

    public String snapshotJson(String analysisId) {
        try {
            return om.writeValueAsString(readSnapshot(analysisId));
        } catch (Exception e) {
            return "{}";
        }
    }

    public Snapshot readSnapshot(String analysisId) {
        AnalysisStateStore.AnalysisState state = stateStore.get(analysisId);
        String json = state == null ? null : state.slotSnapshot();
        if (json == null || json.isBlank() || "{}".equals(json.strip())) {
            return new Snapshot(new SnapshotMeta(0, false), List.of());
        }
        try {
            return om.readValue(json, Snapshot.class);
        } catch (Exception e) {
            log.warn("[{}] 槽位快照解析失败，按空快照处理: {}", analysisId, e.getMessage());
            return new Snapshot(new SnapshotMeta(0, false), List.of());
        }
    }

    private void persistSnapshot(String analysisId, Snapshot snap) {
        try {
            stateStore.saveSnapshot(analysisId, om.writeValueAsString(snap));
        } catch (Exception e) {
            log.warn("[{}] 槽位快照写入失败: {}", analysisId, e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /** 次要字段缺失填默认值（词汇表 defaultHint 兜底），返回带 assumed 标记的新快照 */
    private Snapshot fillSecondaryDefaults(String analysisId, Snapshot snap) {
        List<FieldState> fields = new ArrayList<>();
        for (FieldState f : snap.fields()) {
            if (!"secondary".equals(f.importance())) {
                fields.add(f);
                continue;
            }
            SlotRecord r = slotStore.find(analysisId, f.name()).orElse(null);
            String dv = f.defaultValue() != null && !f.defaultValue().isBlank()
                    ? f.defaultValue()
                    : vocabDefault(f.name());
            if ((r == null || !r.filled()) && dv != null && !dv.isBlank()) {
                String value = SlotVocabulary.maskSensitive(f.name(), dv);
                slotStore.upsert(new SlotRecord(0, analysisId, f.name(), f.importance(), value, true, f.standard()));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("slot", f.name());
                payload.put("value", value);
                payload.put("assumed", true);
                events.publish(analysisId, EventTypes.COLLECT_FILLED, payload);
                fields.add(new FieldState(f.name(), f.importance(), f.question(), f.defaultValue(), f.standard(), true));
            } else {
                fields.add(f);
            }
        }
        return new Snapshot(snap.meta(), fields);
    }

    private static String vocabDefault(String name) {
        SlotVocabulary.SlotDef def = SlotVocabulary.def(name);
        return def != null ? def.defaultHint() : null;
    }

    private static String questionFor(Snapshot snap, String name) {
        for (FieldState f : snap.fields()) {
            if (f.name().equals(name) && f.question() != null && !f.question().isBlank()) {
                return f.question();
            }
        }
        SlotVocabulary.SlotDef def = SlotVocabulary.def(name);
        if (def != null) {
            return def.question();
        }
        return "请补充：" + name;
    }
}
