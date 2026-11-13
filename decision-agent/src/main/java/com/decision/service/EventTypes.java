package com.decision.service;

/**
 * SSE 事件类型常量（spec SSE 事件协议）。
 * 事件名含冒号，SseEmitter.event().name() 直接支持。
 */
public final class EventTypes {

    private EventTypes() {
    }

    // ---- 分析协议事件 ----
    /** 采集触发：问题文本 + 槽位名 + 是否可跳过 */
    public static final String COLLECT_QUESTION = "collect:question";
    /** 用户回答入库：槽位名 + 值 */
    public static final String COLLECT_FILLED = "collect:filled";
    /** 六步流程阶段切换：intent/collect/assess/path/gap/risk */
    public static final String ANALYSIS_STAGE = "analysis:stage";
    /** 推理思考流（returnThinking） */
    public static final String ANALYSIS_THINKING = "analysis:thinking";
    /** 红队质疑产出：质疑点列表 */
    public static final String REDTEAM_ISSUE = "redteam:issue";
    /** 三档情景生成：A/B/C 档完整数据 */
    public static final String SCENARIO = "scenario";
    /** 分析中缺数据追问：问题 + 上下文说明 */
    public static final String ASK_FOLLOWUP = "ask:followup";
    /** 完成：报告 id */
    public static final String DONE = "done";
    /** 失败：错误信息 */
    public static final String ERROR = "error";

    // ---- 连接层事件（照搬 research-agent） ----
    public static final String HELLO = "hello";
    public static final String HEARTBEAT = "heartbeat";
}
