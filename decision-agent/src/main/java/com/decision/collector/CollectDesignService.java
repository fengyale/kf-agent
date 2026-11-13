package com.decision.collector;

import com.decision.model.CollectorModels.CollectDesign;
import com.decision.model.CollectorModels.CollectField;
import com.decision.prompt.PromptBuilder;
import com.decision.service.TracedLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 采集设计（模板2）：按问题类型由方法论反推所需字段（最多 5 个），
 * 每字段标注 key（关键必问）/ secondary（次要可默认假设）。
 *
 * LLM 输出后做三重兜底：
 * 1. 字段名经 SlotVocabulary.normalize 归一（"每周可投入时长"→weeklyHours）
 * 2. importance 归一到 key/secondary
 * 3. 学习计划类仅保证 goal（其余字段按真实变量设计，默认假设可兜底）
 */
@Service
public class CollectDesignService {

    private static final Logger log = LoggerFactory.getLogger(CollectDesignService.class);

    private static final int MAX_FIELDS = 5;

    private final TracedLlmClient llm;
    private final PromptBuilder prompts;

    public CollectDesignService(TracedLlmClient llm, PromptBuilder prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    public CollectDesign design(String analysisId, String type, String question) {
        CollectDesign raw = llm.generateJson(analysisId, "collect",
                prompts.collectSystem(), prompts.buildCollectPrompt(type, question),
                CollectDesign.class, prompts.templateHash("collect"));

        // 归一 + 去重（normalize 后可能撞名，保留首个）
        LinkedHashMap<String, CollectField> byName = new LinkedHashMap<>();
        if (raw != null && raw.fields() != null) {
            for (CollectField f : raw.fields()) {
                if (f == null || f.name() == null || f.name().isBlank()) {
                    continue;
                }
                String name = SlotVocabulary.normalize(f.name());
                byName.putIfAbsent(name, new CollectField(name, normalizeImportance(f.importance()),
                        f.question() == null || f.question().isBlank() ? defaultQuestion(name) : f.question(),
                        f.defaultValue()));
            }
        }
        List<CollectField> fields = new ArrayList<>(byName.values());
        if (fields.size() > MAX_FIELDS) {
            fields = new ArrayList<>(fields.subList(0, MAX_FIELDS));
        }

        // 学习计划类仅兜底 goal（无目标无法评估）；weeklyHours/deadline 交给 LLM 按需设计，
        // 且都有默认假设（10 小时/周、无硬截止）可走 secondary 兜底，不再强制必问
        if (IntentService.TYPE_LEARNING.equals(type)) {
            ensureField(fields, SlotVocabulary.GOAL);
        }
        log.info("[{}] 采集设计完成: {} 个字段（type={}）", analysisId, fields.size(), type);
        return new CollectDesign(fields);
    }

    private static String normalizeImportance(String raw) {
        if (raw == null) {
            return "key";
        }
        String s = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return s.contains("second") || s.contains("次") ? "secondary" : "key";
    }

    private static String defaultQuestion(String name) {
        SlotVocabulary.SlotDef def = SlotVocabulary.def(name);
        return def != null ? def.question() : "请补充：" + name;
    }

    private static void ensureField(List<CollectField> fields, String name) {
        boolean exists = fields.stream().anyMatch(f -> f.name().equals(name));
        if (!exists) {
            SlotVocabulary.SlotDef def = SlotVocabulary.def(name);
            String question = def != null ? def.question() : "请补充：" + name;
            String dv = def != null ? def.defaultHint() : null;
            fields.add(new CollectField(name, "key", question, dv));
        }
    }
}
