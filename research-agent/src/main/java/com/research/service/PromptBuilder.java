package com.research.service;

import com.research.model.ReviewModels.ReviewIssue;
import com.research.model.SearchModels.SearchItem;
import org.springframework.stereotype.Service;

import java.util.List;

// 模板内容见《调研Agent元提示词》第六节（基础版来自 AI深度调研功能开发提示词），迭代以用户后续指示为准。
// 模板为可独立修改的常量块，与循环逻辑解耦：只改常量不改代码即可迭代提示词。
@Service
public class PromptBuilder {

    private static final String STRATEGY_SYSTEM = """
            你是资深调研策略师，擅长把模糊的调研需求拆解成可执行的搜索计划。
            输出严格 JSON，禁止输出 JSON 以外的任何文字（包括解释与 markdown 代码块标记）。""";

    private static final String REPORT_SYSTEM = """
            你是资深调研分析师，擅长综合多源信息撰写结构化调研报告。
            直接输出 Markdown 报告正文，不要输出任何与报告无关的话。""";

    private static final String REVIEW_SYSTEM = """
            你是严格的调研报告审稿人。只输出 JSON，不要其他文字。""";

    // 方案B：独立裁判核验提示词，用于否决策略师"信息已足够"的自主判断（防止王婆卖瓜）
    private static final String VERIFIER_SYSTEM = """
            你是严格的信息充分度裁判，独立于调研策略师。
            你只做一件事：审视已有搜索资料，判断是否足以支撑一份高质量的调研报告。
            你的默认立场是"不够"——只有在证据确实充分、覆盖核心维度时才判"够"。
            你不负责规划搜索方向，也不负责撰写报告。只输出 JSON，不要其他文字。""";

    private static final String JSON_STRUCTURE = """
            {
              "topicAnalysis": "对主题的理解与维度拆解（100字内）",
              "findingsSummary": "已有发现的核心结论总结（首轮填空字符串）",
              "informationGaps": ["仍存在的信息缺口"],
              "enough": false,
              "nextStep": "search",
              "searches": [
                {"query": "关键词组合", "reason": "搜索目的与期望获得的信息", "platform": "web", "timeRange": "OneYear"}
              ]
            }""";

    public String buildFirstStrategy(String topic) {
        return """
                ## 调研主题
                %s

                ## 自主决策权说明
                本调研没有固定的轮次与搜索次数：何时停止由你根据信息充分度自主判断，
                外部护栏仅是失控兜底。唯一停止标准：现有信息能否支撑一份高质量报告。

                这是第 1 轮搜索，用通用 Web 搜索打底。制定搜索策略：

                1. 识别主题核心维度（概念定义/最新动态/数据案例/争议观点等），写入 topicAnalysis
                2. 强制覆盖两类视角：
                   A. 主题本体视角：概念定义、最新动态、数据与案例、争议或不同观点四个侧面
                   B. 利益相关方视角：这个主题影响谁、谁最有发言权？至少 1 条搜索词站在对立面或局中人的立场设计
                3. 生成 3-5 条搜索（searches），每条包含 query/reason/platform/timeRange
                4. 搜索词必须是关键词组合，控制在 20 字以内，禁止完整句子
                5. 时效敏感主题（价格/政策/岗位/版本）timeRange 用 "OneMonth"，其余 "OneYear"
                6. 首轮 platform 全部填 "web"

                只输出如下 JSON 结构：
                %s""".formatted(topic, JSON_STRUCTURE);
    }

    public String buildNextStrategy(String topic, List<SearchItem> pool, List<String> searchedQueries) {
        return """
                ## 调研主题
                %s

                ## 自主决策权说明
                何时停止由你自主判断，不要参考"轮次多少"，只评估信息充分度：
                -继续的唯一理由：存在影响报告质量的关键缺口，且新搜索有较大概率补上
                -应停止的信号：缺口已属于长尾细节；或预期新搜索大概率只返回重复/低质内容（边际价值低于成本）

                ## 已搜索过的关键词（禁止重复使用）
                %s

                ## 已有搜索发现（%d 条，含平台与可信度评分）
                %s

                基于以上信息做缺口分析，决定继续搜索还是成稿：

                1. findingsSummary：总结已有发现的核心结论
                2. informationGaps：仍存在的关键信息缺口
                3. 信息已足够支撑高质量报告时：enough=true、nextStep="report"、searches=[]
                4. 若继续搜索（3-5 条）：
                   - 评估上一轮哪个平台贡献最大，下一轮优先补充未覆盖的平台
                   - platform 可选：web（通用）、zhihu（专业讨论/深度分析）、bilibili（视频教程/评测）、xiaohongshu（真实体验/种草拔草），可混搭
                   - 搜索词必须是关键词组合，控制在 20 字以内，禁止完整句子
                   - 已搜索过的关键词禁止重复
                5. 对已有结果中评分低或疑似营销内容的来源，缺口分析时忽略
                6. 时效敏感主题 timeRange 用 "OneMonth"，其余 "OneYear"

                只输出如下 JSON 结构：
                %s""".formatted(topic, String.join("、", searchedQueries), pool.size(),
                formatFindings(pool), JSON_STRUCTURE);
    }

    public String buildReport(String topic, List<SearchItem> pool) {
        return """
                ## 调研主题
                %s

                ## 编号资料池（%d 条，多轮搜索所得，每条含编号/标题/平台/站点/评分/时间/正文）
                %s

                撰写调研报告：

                1. 只基于资料池中的编号资料，不得编造资料中不存在的信息
                2. 正文中每个事实性观点后紧跟资料编号引用，格式如 [1] [3]，一个观点可引多个来源
                3. 资料间矛盾的信息：指出矛盾并给出置信度倾向
                4. 时效性数据（价格/版本/数量/排名）必须标注数据时间
                5. 篇幅 1500-3000 字，结构：
                   # 报告标题
                   ## 摘要（200 字左右，概括最重要结论）
                   ## 分主题章节（3-6 章，每章聚焦一个维度）
                   ## 关键发现（3-5 条，每条一句话）
                   ## 参考来源（仅列正文引用过的编号，每行格式：[n] 标题 | 站点 | 时间 | URL）"""
                .formatted(topic, pool.size(), formatMaterials(pool));
    }

    public String buildReportRevise(String topic, List<SearchItem> pool, String draft, List<ReviewIssue> issues) {
        return buildReport(topic, pool) + """

                ## 报告初稿
                %s

                ## 审稿意见清单
                %s

                基于审稿意见修订上述报告初稿（不重新搜索，只基于资料池改写）：
                - A 类意见（缺引用/遗漏信息/编号错/洗稿误判）逐一修正
                - B 类意见对应的关键断言：资料池中有支撑的补上引用；仍只有单一来源支撑的，在该断言后标注「（单源，待验证）」
                - 保持报告整体结构与篇幅，直接输出修订后的完整报告"""
                .formatted(draft, formatIssues(issues));
    }

    public String buildReview(String topic, String draft, List<SearchItem> pool) {
        return """
                你是严格的调研报告审稿人。对照以下搜索资料，检查这份报告初稿：
                ① 每个观点是否都有 [n] 引用支撑？列出无支撑的观点（A类缺陷：写作缺陷）
                ② 资料中的关键信息是否有被报告遗漏？列出遗漏项（A类）
                ③ 引用编号与来源列表是否对应正确？（A类）
                ④ 报告中的关键断言（数字/排名/绝对化表述）是否有 ≥2 个独立来源支撑？
                   单来源支撑的断言列出来（B类缺陷：原料缺口）
                ⑤ 相似内容的多篇文章（洗稿）是否被当作了多个独立来源？（A类）
                没有问题时输出空数组。只输出 JSON，不要其他文字。

                ## 调研主题
                %s

                ## 报告初稿
                %s

                ## 编号资料池（%d 条）
                %s

                输出 JSON：{"issues": [{"type": "A", "description": "缺陷描述", "section": "所在章节"}], "needRevise": true}"""
                .formatted(topic, draft, pool.size(), formatMaterials(pool));
    }

    public String buildSupplement(List<ReviewIssue> bIssues, List<String> searchedQueries) {
        return """
                调研报告存在无资料支撑的关键断言（B 类原料缺口）。生成 1-2 条定向补充搜索，只搜最关键的缺口：

                ## 缺口清单
                %s

                ## 已搜索过的关键词（禁止重复）
                %s

                搜索词必须是关键词组合，控制在 20 字以内，禁止完整句子；时效敏感内容 timeRange 用 "OneMonth"，其余 "OneYear"。
                只输出 JSON：{"searches": [{"query": "关键词组合", "reason": "补搜目的", "platform": "web|zhihu|bilibili|xiaohongshu", "timeRange": "OneYear"}]}"""
                .formatted(formatIssues(bIssues), String.join("、", searchedQueries));
    }

    public String strategySystem() {
        return STRATEGY_SYSTEM;
    }

    public String chatSystem() {
        return """
                你是"AI 调研助手"的对话助手。用户与你闲聊或提出简单问题。
                回答要求：
                1. 简洁直接，正常对话口吻，不要长篇大论
                2. 用户若提出需要深度调研的话题（带"调研/分析/对比"等意图），提示对方直接发送完整调研主题即可自动启动调研
                3. 不编造具体数据与引用；不确定就说不确定""";
    }

    public String reportSystem() {
        return REPORT_SYSTEM;
    }

    public String reviewSystem() {
        return REVIEW_SYSTEM;
    }

    /** 方案B：独立裁判的核验提示词，独立审视资料池判断信息充分度 */
    public String verifierSystem() {
        return VERIFIER_SYSTEM;
    }

    /** 方案B：裁判核验的 user prompt，输入资料池，要求独立判断 */
    public String buildVerifyEnough(String topic, List<SearchItem> pool) {
        return """
                ## 调研主题
                %s

                ## 资料池（%d 条，含平台、站点、评分、时间、正文摘要）
                %s

                ## 任务
                策略师认为上述资料已充分，可以成稿。请你作为独立裁判，严格审视：

                1. 资料是否覆盖了主题的核心维度？（概念定义/最新动态/数据案例/争议观点/利益相关方视角）
                2. 是否存在关键信息缺口？（缺具体数据、缺对立观点、缺时效性信息、缺权威来源等）
                3. 资料的质量和可信度是否足以支撑报告？

                判定标准：
                - sufficient=true：所有核心维度均有 ≥2 条可信资料支撑，且无关键缺口
                - sufficient=false：任一核心维度仅有单一来源、或存在明显缺口、或资料质量存疑

                只输出 JSON：{"sufficient": true/false, "reason": "判断依据（50字内）", "criticalGaps": ["缺口描述"]}"""
                .formatted(topic, pool.size(), formatMaterials(pool));
    }

    public String formatMaterials(List<SearchItem> pool) {
        int budget = pool.size() > 25 ? 500 : 1000;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pool.size(); i++) {
            SearchItem item = pool.get(i);
            String time = blank(item.publishTime()).isEmpty() ? "" : "，" + item.publishTime();
            sb.append("[").append(i + 1).append("] ").append(item.title())
                    .append("（").append(item.platform()).append("/").append(blank(item.siteName())).append("，评分 ")
                    .append(String.format("%.2f", item.rankScore())).append(time).append("）\n")
                    .append(truncate(item.body(), budget)).append("\n\n");
        }
        return sb.toString();
    }

    public String formatFindings(List<SearchItem> pool) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pool.size(); i++) {
            SearchItem item = pool.get(i);
            sb.append("[").append(i + 1).append("] ").append(item.title())
                    .append("（").append(item.platform()).append("/").append(blank(item.siteName()))
                    .append("，评分 ").append(String.format("%.2f", item.rankScore())).append("）— ")
                    .append(truncate(item.body(), 150)).append("\n");
        }
        return sb.toString();
    }

    private String formatIssues(List<ReviewIssue> issues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < issues.size(); i++) {
            ReviewIssue issue = issues.get(i);
            sb.append(i + 1).append(". [").append(issue.type()).append("类] ").append(issue.description())
                    .append(blank(issue.section()).isEmpty() ? "" : "（章节: " + issue.section() + "）").append("\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    private String blank(String s) {
        return s == null ? "" : s.strip();
    }
}