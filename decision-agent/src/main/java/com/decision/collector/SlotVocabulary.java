package com.decision.collector;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 标准槽位词汇表（spec Requirement: 标准槽位词汇表）。
 *
 * 词汇表内字段 is_standard=true，参与下游确定性逻辑（分档计算、CalcTool、校准引用）；
 * 词汇表外扩展字段仅展示与提示词上下文，不参与计算。
 * normalize(name) 在采集入库时把 LLM 命名漂移（中文别名/蛇形命名）归一到标准槽位名，
 * 下游计算不受命名漂移影响。
 *
 * 敏感脱敏也在此完成（spec：脱敏发生在槽位结构化这一步）：收入/健康类敏感词命中时
 * 值替换为 [已脱敏]，且该槽位不进入任何分析线提示词。
 */
public final class SlotVocabulary {

    // ---- 标准槽位名常量（确定性消费的分界：仅这些字段参与计算） ----
    public static final String GOAL = "goal";
    public static final String WEEKLY_HOURS = "weeklyHours";
    public static final String DEADLINE = "deadline";
    public static final String SKILL_LEVEL = "skillLevel";
    public static final String BUDGET = "budget";
    public static final String CURRENT_STATUS = "currentStatus";
    public static final String LEARNING_HISTORY = "learningHistory";
    public static final String AVAILABLE_TIME = "availableTime";
    public static final String CONSTRAINTS = "constraints";
    public static final String ALTERNATIVE = "alternative";

    /** 词汇表字段定义：标准名 / 中文标签 / 默认追问 / 默认假设值 / 别名（含中文与命名漂移形式） */
    public record SlotDef(String name, String label, String question, String defaultHint, List<String> aliases) {}

    private static final Map<String, SlotDef> STANDARD = new LinkedHashMap<>();
    /** 归一化别名（小写去空白/下划线/连字符）→ 标准名 */
    private static final Map<String, String> ALIAS_INDEX = new LinkedHashMap<>();

    static {
        register(GOAL, "目标", "你想达成的具体目标是什么？", null,
                List.of("目标", "我想做的事", "事项", "决策目标", "target"));
        register(WEEKLY_HOURS, "每周可投入时长", "每周能稳定投入多少小时？", "10",
                List.of("每周可投入时长", "每周投入", "周投入", "每周时间投入", "每周可投入时间",
                        "每周学习时长", "每周可用时间", "周时长", "每周投入时间", "每周花费时间"));
        register(DEADLINE, "硬截止日期", "有硬截止日期吗（yyyy-MM-dd）？没有可跳过", null,
                List.of("截止日期", "硬截止", "期限", "完成期限", "截止时间", "deadline日期", "最后期限"));
        register(SKILL_LEVEL, "当前水平", "在这个方向上现在是什么水平？", "零基础",
                List.of("技能水平", "当前水平", "水平", "基础", "现有能力", "能力基础"));
        register(BUDGET, "预算", "预算上限是多少？", "0",
                List.of("预算", "预算上限", "费用上限", "资金", "花费上限", "可投入资金"));
        register(CURRENT_STATUS, "当前状态", "当前的工作/生活状态是什么？", "在职",
                List.of("当前状态", "现状", "目前情况", "当前方向", "当前处境", "所处阶段"));
        register(LEARNING_HISTORY, "学习/执行历史", "过去在这个方向上做过什么？完成得如何？", "无记录",
                List.of("学习历史", "历史完成率", "过往经历", "执行历史", "完成情况", "历史投入", "过往经验"));
        register(AVAILABLE_TIME, "可用时间", "总体可用时间情况如何？", "每周10小时",
                List.of("可用时间", "时间投入", "时间现状", "每天可用时间", "空闲时间", "时间安排"));
        register(CONSTRAINTS, "硬约束", "有哪些硬约束（家庭/通勤/身体等）？", "无",
                List.of("硬约束", "限制条件", "约束", "限制", "制约因素"));
        register(ALTERNATIVE, "备选方案", "有没有已经在考虑的备选路径？", "无",
                List.of("备选方案", "替代路径", "备选", "替代方案", "其他选择", "退路"));
    }

    private SlotVocabulary() {
    }

    private static void register(String name, String label, String question, String defaultHint, List<String> aliases) {
        STANDARD.put(name, new SlotDef(name, label, question, defaultHint, aliases));
        ALIAS_INDEX.put(normalizeKey(name), name);
        for (String alias : aliases) {
            ALIAS_INDEX.put(normalizeKey(alias), name);
        }
    }

    private static String normalizeKey(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
    }

    /**
     * 别名归一：把 LLM 命名漂移（如"每周可投入时长"）映射到标准槽位名（weeklyHours）。
     * 词汇表外字段原样返回（视为扩展字段，仅展示不参与计算）。
     */
    public static String normalize(String rawName) {
        if (rawName == null) {
            return "";
        }
        String mapped = ALIAS_INDEX.get(normalizeKey(rawName));
        return mapped != null ? mapped : rawName.strip();
    }

    public static boolean isStandard(String name) {
        return name != null && STANDARD.containsKey(name);
    }

    public static SlotDef def(String name) {
        return STANDARD.get(name);
    }

    public static List<SlotDef> all() {
        return List.copyOf(STANDARD.values());
    }

    /** 词汇表提示词片段（模板2 约束"优先从词汇表选择字段"用） */
    public static String vocabularyHint() {
        StringBuilder sb = new StringBuilder();
        for (SlotDef d : STANDARD.values()) {
            sb.append("- ").append(d.name()).append("：").append(d.label()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // ==================== 敏感脱敏（收入/健康类） ====================

    public static final String MASKED = "[已脱敏]";

    /** 槽位名含收入/健康语义 → 整值脱敏（扩展字段命名层面拦截） */
    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "收入|工资|薪资|月薪|年薪|存款|负债|积蓄|健康|病史|疾病|患病|体检|诊断|用药|income|salary|wage|savings|debt|health",
            Pattern.CASE_INSENSITIVE);

    /** 槽位值含收入/健康类敏感词 → 整值脱敏（宁可错杀：脱敏失败比泄露安全） */
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "收入|工资|薪资|月薪|年薪|存款|负债|积蓄|理财|病史|患病|疾病|体检|诊断|处方|抑郁|income|salary|wage|savings|debt|diagnosis",
            Pattern.CASE_INSENSITIVE);

    /** 槽位结构化入库时的敏感过滤：命中则值替换为 [已脱敏]，该槽位不进提示词历史 */
    public static String maskSensitive(String name, String value) {
        if (value == null) {
            return null;
        }
        if (name != null && SENSITIVE_NAME.matcher(name).find()) {
            return MASKED;
        }
        String v = value.strip();
        return SENSITIVE_VALUE.matcher(v).find() ? MASKED : v;
    }

    public static boolean isMasked(String value) {
        return MASKED.equals(value);
    }
}
