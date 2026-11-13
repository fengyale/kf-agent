package com.decision.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 采集线（线1）数据模型：意图识别（模板1）输出、采集设计（模板2）输出、槽位记录。
 * 均为 LlmClient.generateJson 的直接反序列化目标或 slot 表行记录。
 */
public class CollectorModels {

    /** 模板1 输出：{goal, pathHint, type, urgency, depth} */
    public record IntentResult(String goal, String pathHint, String type, String urgency, String depth) {}

    /** 模板2 输出字段：{name, importance(key/secondary), question, default} */
    public record CollectField(String name, String importance, String question,
                               @JsonProperty("default") String defaultValue) {}

    /** 模板2 输出：{fields: [...]}（最多 5 个字段） */
    public record CollectDesign(List<CollectField> fields) {}

    /** slot 表行记录：is_standard=1 为标准词汇表字段（参与确定性计算），扩展字段仅展示 */
    public record SlotRecord(long id, String analysisId, String name, String importance,
                             String value, boolean filled, boolean standard) {}
}
