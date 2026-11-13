# AI 调研 Agent

自动多轮调研的智能体 Web 应用：提交一个调研主题，Agent 自主决定搜索策略与轮次，
综合多源资料产出带引用编号的结构化调研报告，全过程实时可视化。

设计蓝本：`F:\learn\ai转型计划个人学习\自学资料和心得\Agent搭建学习笔记\调研Agent元提示词.md`（v2.0）

## 核心特性

- **弹性调研循环**：何时停止由模型自主判断（每轮输出 `enough` 字段），护栏只做失控兜底（8轮/30次/超时/边际收益）
- **多源搜索**：豆包搜索（主）/ 博查（备），支持 web / zhihu / bilibili / xiaohongshu 平台定向
- **质量保障**：审稿-修订闭环（A类写作缺陷/B类原料缺口）、双视角搜索、单源断言标注、结果去重聚类
- **实时监控**：SSE 事件驱动的动态 SVG 画布（轮次泳道、节点状态、思考过程、审稿意见）
- **知识回流**：报告落盘 learnkb 语料目录，供 RAG 知识库自动吸收

## 快速开始

### 环境要求

- JDK 21（默认路径 `D:\Program Files\Java\jdk-21.0.11`，可在 start.bat 中改）
- Maven 3.9+（统一仓库 `D:\ownerMaven\mavenRepository`，经 `~/.m2/settings.xml` 的 localRepository 配置，阿里云镜像加速）

### 配置 Key（环境变量）

| 变量 | 用途 | 获取方式 |
|---|---|---|
| `DEEPSEEK_API_KEY` | LLM（策略/报告/审稿） | DeepSeek 平台 |
| `SEARCH_API_KEY` | 豆包搜索（主） | 火山方舟/豆包搜索 |
| `BOCHA_API_KEY` | 博查搜索（备） | 博查平台 |

一次性持久化（之后重开命令行窗口生效）：

```bat
setx DEEPSEEK_API_KEY "你的key"
setx SEARCH_API_KEY "你的key"
```

### 启动

双击 `start.bat` → 浏览器访问 `http://localhost:8088`

> 若 start.bat 提示未检测到 SEARCH_API_KEY 且已 setx 过，关闭窗口重新双击即可（setx 对已开窗口不生效）。

## 使用

1. 首页提交调研主题 → 自动跳转监控页
2. 监控页实时观察：轮次泳道追加、搜索节点亮起、模型思考过程、报告流式生成
3. 完成后查看报告（页面渲染 + 落盘文件），退出原因会标注「模型自主停止」或「护栏止损」
4. 首页底部有历史任务与审稿统计

## 架构

```
ResearchController ─ 提交/查询 API
  └─ ResearchLoop ─ 弹性调研循环（核心）
       ├─ PromptBuilder ─ 提示词模板（策略/报告/审稿/补搜，可独立迭代）
       ├─ LlmClient ─ DeepSeek 调用（JSON 结构化 + 流式思考）
       ├─ SearchClient ─ 搜索接口（DoubaoSearchClient / BochaSearchClient）
       ├─ ResultProcessor ─ 过滤(RankScore<0.4) / 去重 / 标题聚类(相似度>0.85)
       ├─ ReviewService ─ 审稿 / 修订 / B类缺口补搜
       └─ ReportWriter ─ 落盘（learnkb 语料目录，失败降级 data/reports）
EventPublisher ─ SSE 事件（实时推送 + SQLite 持久化，支持断线重放）
MonitorController + monitor.js ─ 动态 SVG 画布渲染
TaskStore ─ SQLite（任务/事件/报告/审稿问题）
```

## 配置（application.yml）

| 配置 | 说明 | 默认 |
|---|---|---|
| `agent.search.provider` | doubao / bocha | doubao |
| `agent.llm.provider` | deepseek / openai-compatible / ollama | deepseek |
| `agent.budget.*` | 护栏兜底值（轮次/搜索次数/超时） | 8轮/30次/20分钟 |
| `agent.report.output-dir` | 报告落盘目录 | F:\learn\ai转型计划个人学习\research-agent调研报告 |

> 配置修改需重启服务生效。切 provider 只改 yml 两处（llm.provider + search.provider）。

## 退出原因语义（监控页 chip）

- `model-decision:enough`（绿色）：模型自主判断信息充分，**常态**
- `model-decision:empty-plan`（绿色）：模型未给出新搜索计划，视为信息足够
- `guard:*`（黄色）：护栏止损（超时/轮次/预算/边际收益），**异常情况需关注**
- `error:*`（红色）：失败

## 数据

- SQLite：`data/research.db`（任务、事件、报告、审稿问题，重启不丢）
- 报告落盘：`agent.report.output-dir`，失败自动降级 `data/reports/`

## 已知问题

- **F 盘落盘失败**：从 TRAE 会话内启动的服务受 IDE 沙箱限制，无法写 `F:\learn\...`（报 Access denied，非火绒/Defender 所为）。
  解决：直接双击 `start.bat` 启动（不经过 TRAE 沙箱，写入正常）；或在 TRAE 设置 `Permission & Approval → Custom Configuration` 中放开 `F:\learn` 写权限
- IDEA 控制台中文日志乱码：日志按 GBK 输出（配合 cmd 窗口），IDEA 里乱码属预期取舍，看 `data/` 或页面不受影响
