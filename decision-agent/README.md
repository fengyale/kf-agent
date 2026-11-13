# 决策 Agent（个人决策参谋）

> 个人决策与可行性分析 Agent：你提出"我想做什么事、通过什么方式"，它通过交互式采集了解你（时间、资源、历史执行力），结合个人上下文库与外部数据，输出结构化可行性分析——路径设计、可达效果、能力差距、风险与对策。
>
> **核心理念**：与通用聊天模型的差异化在于**它知道你**——个人上下文库 + 历史执行数据，让同样的问题得到"为你定制"而非"标准答案"的分析。

---

## 一、生态定位

```
调研 Agent（数据供应商）：知道"世界是什么样" → 产出调研报告
决策 Agent（本项目，消费者）：知道"你是谁" → 个人库 + 消费调研报告
learnkb（知识沉淀）：两个 Agent 的产出回流语料目录，越用越厚
```

分析涉及外部世界时**三段递进**：先查调研 Agent 已有报告（引用 = 零成本零延迟，只读 `research-agent/data/research.db`）→ 报告没有才联网搜索（1~3 条）→ 搜索也没有再提示发起完整调研。

## 二、核心功能

- **六步分析流程**：意图识别 → 交互采集 → 可行性评估 → 路径设计 → 差距分析 → 风险与对策
- **三档情景 A/B/C**：乐观（全力投入）/ 现实（校准系数修正）/ 保守（最低可持续投入），每档含里程碑 + 验收标准 + 触发降档条件；分档依据优先用个人库历史分位（P25/P50/P75）
- **红队收敛循环（自审机制）**：质疑 → 修订 → 复审，直到无严重质疑或预算/轮次耗尽。对抗强度分层——**代码确定性校验**先跑（三档单调性、现实档是否真按校准系数修正、路径周期一致性），命中即以 major 计入质疑且**模型无权降级**；模型红队只专注代码判不出的盲区（假设是否成立、谁会劝阻、哪一档在自欺）。收敛条件收紧：**空质疑不判收敛**（未经实质审查）、**修订无实质变化时复审不得判收敛**（防"改措辞即过关"的假收敛）。红队输入隔离（只喂结构化结论 + 历轮修订说明，不喂推理过程，防锚定）；minor 质疑写入报告"已知局限"；轮次/收敛/空转/假修订等机制指标在统计页"红队体检"可见
- **分桶校准系数**：按问题类型（学习计划/日常事务/远期规划/商业经营）各维护完成率，只用行为日志计算（自评数据不污染）；冷启动用全局 0.7 并显著标注
- **流程路由**：日常事务走轻量路径（1~2 次调用出结论）、学习计划走标准六步、远期规划/商业经营走深度路径（含报告引用 + 红队收敛），可手动升降级
- **工作记忆压缩**：分析线每步只消费槽位 JSON + 上一步 JSON 输出，绝不拼原始对话文本；敏感数据（收入/健康）在槽位结构化一步脱敏，不入提示词历史
- **人机交织挂起/恢复**：等用户回答时流程退出释放线程，状态全量落库；回答触发断点恢复，已完成步骤不重跑，SSE 断线重连回放可跨天续接
- **思考流实时展示**：分析步骤流式输出推理过程（`analysis:thinking` 事件），页面不干等
- **确定性计算工具**：总投入 = 周投入 × 周期等算术由代码求值（"经计算校验"），不走 LLM 心算
- **预测后验留痕**：三档量化预期落库，回顾时填实际结果，为 v2 预测校准曲线（Brier score）留种子
- **数据保鲜度**：外部数据源带 asOf，过期自动降权 + 提示"此数据已 X 个月未校准"
- **成本护栏**：单次分析模型调用 ≤ 8 次（deep 路径 12 次，用于容纳"质疑-修订-复审"红队闭环）、时长 ≤ 3 分钟（超时输出已完成部分）、搜索按需 1~3 条

## 三、技术栈

| 项 | 选型 |
|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.3.5 |
| LLM | langchain4j 1.18.1 + DeepSeek（`deepseek-v4-flash`，returnThinking 思考流）；Ollama 仅应急降级 |
| 搜索 | 豆包（主）+ 博查（备），整层复用 research-agent 的 SearchClient |
| 存储 | SQLite（JdbcTemplate 直连，无 ORM），9 张表 |
| 前端 | Thymeleaf + 原生 JS + Tailwind（CDN）+ SSE；**纸面学术风设计系统**（藏青+米白、衬线标题、等宽数字、细线分隔、无渐变无光晕、色彩只用于语义） |
| 导出 | Apache POI（xlsx/docx）+ openhtmltopdf（PDF）+ java.util.zip（打包） |
| 端口 | **8089**（与 research-agent 8088 错开，可同时运行） |

## 四、快速开始

### 1. 配置 API Key（新开窗口生效）

```
setx DEEPSEEK_API_KEY "your-key"   (LLM，主)
setx SEARCH_API_KEY "your-key"     (豆包搜索)
setx BOCHA_API_KEY "your-key"      (博查搜索，备)
```

### 2. 启动

```
双击 start.bat
```

启动后访问 http://localhost:8089

> **F 盘落盘限制**：报告落盘目录在 `F:\learn\ai转型计划个人学习\decision-agent决策分析`。TRAE 沙箱内启动的服务写不了 F 盘，**必须双击 start.bat 在沙箱外启动**；落盘失败时自动降级到 `data/reports`。

### 3. 前端改动的生效方式

静态资源/模板改动后需同步到 `target/classes` 才生效（spring-boot:run 从 target 读资源）：

- 沙箱外：双击 `restart-ui.ps1`（robocopy 同步 + 从注册表注入 API key + 重启）
- 沙箱内：`mvn -s settings.xml -Dmaven.repo.local=target/m2-repo resources:resources`（仅拷资源，无需重启）

> Maven 需 JDK 21：`JAVA_HOME=D:\Program Files\Java\jdk-21.0.11`（系统默认 JDK 8 会编译失败）。

### 4. 测试

```
mvn -s settings.xml -Dmaven.repo.local=target/m2-repo test
```

9 个单测全部**不真调模型不联网**（LlmClient 录制/回放模式 + 自测样本数据），覆盖导出全家桶、挂起/恢复状态机、录放回放。

## 五、配置说明（application.yml 关键项）

| 配置 | 说明 | 默认 |
|---|---|---|
| `server.port` | 服务端口 | 8089 |
| `agent.llm.provider` | deepseek / openai-compatible / ollama | deepseek |
| `agent.llm.deepseek.api-key` | `${DEEPSEEK_API_KEY:}` | 空 |
| `agent.llm.record-mode` | 录制模式：真实调用存 recordings-dir | false |
| `agent.llm.replay-mode` | 回放模式：读录制文件不真调模型（测试） | false |
| `agent.search.provider` | doubao / bocha | doubao |
| `agent.budget.maxModelCalls` | 单次分析模型调用上限（light/standard） | 8 |
| `agent.budget.deepMaxModelCalls` | deep 路径模型调用上限（含红队闭环） | 12 |
| `agent.budget.maxDurationMinutes` | 单次分析活跃时长上限 | 3 |
| `agent.report.output-dir` | 报告落盘目录 | F:\learn\ai转型计划个人学习\decision-agent决策分析 |
| `agent.data.db-path` | 主库 | data/decision.db |
| `agent.data.research-db-path` | 调研 Agent 报告库（只读引用） | D:\workspace\kf-agent\research-agent\data\research.db |
| `agent.data.research-markdown-dir` | 调研报告 MD 兜底扫描目录 | F:\learn\ai转型计划个人学习\research-agent调研报告 |

> 改 yml 必重启（Spring Boot 不热加载）。搜索额度为**账号级共享 500 次/月**（与调研 Agent 同一池子），测试勿大手大脚。

## 六、架构（三线交织）

```
线1 会话线（交互采集用）    ChatController → 采集状态机 → 上下文槽位
                          （多轮、有状态、可跳过、可降级）

线2 分析线（核心价值用）    FeasibilityLoop → 方法论模板 → 结构化输出
                          （单次触发、重推理、引用外部数据、红队收敛）

线3 数据线                个人库（SQLite）+ 调研报告引用 + 按需联网
```

**交织机制**：线1 喂线 2（槽位填得越满分析质量越高）；线2 喂线 1（每环节前 contextAdequacy 检查点，缺数据中断分析触发追问，拿到答案继续）——"过程即采集"，聊到哪问到哪。

### 模块结构

```
decision-agent/
├── config/          AgentProperties（五段式）、LangChainConfig、DbInitializer（9 表）
├── controller/      ChatController、AnalysisController、BehaviorController、
│                    ProfileController、HistoryController、StatsController、ExportController、WebController
├── loop/            FeasibilityLoop（六步编排+挂起恢复）、FlowRouter、BudgetGuard、StateStore 等
├── collector/       采集状态机（槽位管理/脱敏/标准词汇表归一）
├── personal/        ProfileService、CalibrationService（分桶）、BehaviorLogService（双入口）
├── external/        ReportReferralService（调研报告引用）、ExternalDataService（三段递进）
├── prompt/          PromptBuilder（四模板独立常量块，可单独修改）
├── export/          ExcelExporter / PdfExporter / WordExporter / ReportWriter（双级落盘）
├── service/         LlmClient（录放）、TracedLlmClient（trace）、EventPublisher、SearchClient、CalcTool
└── web/             Thymeleaf 页面 + 静态资源（纸面风设计系统）
```

## 七、页面一览

| 页面 | 路由 | 说明 |
|---|---|---|
| 首页 | `/` | 提问输入 + 深度选择（自动/轻量/标准/深度）+ 示例问题 + 首访画像引导（可跳过） |
| 会话页 | `/session/{id}` | 采集对话流（追问卡片：跳过/直接给结论）+ 六步阶段指示器 + 思考流折叠 + SSE 实时 |
| 报告页 | `/report/{id}` | 门面：结论/三档对比卡/风险矩阵/差距表+kill criteria/红队质疑/已知局限/trace 明细 + 五格式导出 |
| 历史页 | `/history` | 分析列表（统计汇总：总数/已出报告/已标记完成/零成本引用）+ 回顾详情（三档预期/进展/实际结果）+ 删除 |
| 统计页 | `/stats` | 高频缺陷分布（红队质疑聚类）+ 红队体检（轮次/收敛/空转/假修订/代码裁判占比）+ 分桶校准系数 + 完成率趋势 + 单次分析调用明细 |
| 行为数据 | `/behavior` | 手填表单 + 粘贴文本 LLM 提取导入（确认后入库）+ 四桶校准总览 |

## 八、产出物五形态

| 形态 | 用途 | 实现 |
|---|---|---|
| 报告页 HTML | 看（门面，纸面学术风） | Thymeleaf + 结构化渲染（三档对比卡/风险矩阵/差距表/思考流） |
| Markdown | 存档 + learnkb 回流 | ReportWriter 双级落盘（F 盘 → 失败降级 data/reports） |
| Excel xlsx | 可加工可跟踪（三档对比/里程碑/周投入排期/风险清单） | Apache POI，单元格不锁 |
| PDF | 打印/存档/转发 | openhtmltopdf 从报告 HTML 直转（注册中文字体） |
| Word docx | 正式场合发送 | POI XWPF 结构化段落 + 表格 |

报告页提供各格式**单项下载** + **打包下载**（zip 含四件，中文文件名）。

## 九、数据库（SQLite 9 表）

| 表 | 用途 |
|---|---|
| profile | 画像（冷启动引导填充） |
| behavior_log | 行为日志（type 桶 / is_self_eval 排除校准；随分析删除保留，属校准资产） |
| analysis | 分析历史 |
| analysis_state | 挂起/恢复状态（当前步骤/steps_output/槽位快照） |
| analysis_expectation | 预测后验（三档预期 + 实际结果） |
| event | SSE 事件（双写，断线回放源） |
| report | 报告（title/markdown/file_path） |
| slot | 采集槽位（is_standard 标准词汇表分界） |
| llm_trace | 模型调用 trace（步骤/token/耗时/模板 hash） |

## 十、踩坑清单（实测继承）

1. Maven 需 JDK 21，项目内 `settings.xml` 阿里云镜像 + 共享仓库 `d:\ownerMaven`
2. 含中文的 bat 必须 GBK 编码 + CRLF（start.bat 已调通）
3. TRAE 沙箱内写不了 F 盘 → 双击 start.bat 启动；ReportWriter 有降级兜底
4. 沙箱内启动会从用户主目录读 setx 变量失败 → restart-ui.ps1 从注册表注入
5. SSE 事件 JSON 无 BOM；`returnThinking(true)` 才有思考流
6. DeepSeek 是混合推理模型，默认就思考
7. 前端静态资源改动需同步 target/classes（`mvn resources:resources` 或 restart-ui.ps1）
8. 搜索额度账号级共享（500 次/月），引用调研报告可零搜索成本
9. openhtmltopdf 严格 XML：HTML 必须自闭合（`<hr/>`），否则 PDF 导出 500

## 十一、关联文档

- 设计规格 / 任务 / 验收清单：`.trae/specs/build-decision-agent/`（spec.md / tasks.md / checklist.md）
- UI v2 纸面风验收截图：`docs/ui-v2-screenshots/`
- 需求元提示词原文：`F:\learn\ai转型计划个人学习\自学资料和心得\Agent搭建学习笔记\决策Agent元提示词.md`
- 复用参考项目：`D:\workspace\kf-agent\research-agent`（端口 8088）