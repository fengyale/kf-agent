# AGENTS.md

面向在本仓库工作的 AI 编码助手：先读本文件，再动手。项目背景与历史决策见 `.trae/HANDOFF.md`，硬性规则见 `project_rules.md`。

## 1. 项目概览

「AI 调研 Agent」：输入一个调研主题 → 模型自主进行多轮搜索（每轮自行判断缺口与是否继续）→ 汇总资料 → 生成带引用报告 → 单次审稿修订 → 落盘到知识库语料目录。附带对话式首页、实时工作流监控页、历史数据统计页。

## 2. 技术栈

| 项 | 版本/说明 |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.5（Web / Thymeleaf / JDBC） |
| LangChain4j | 1.4.0 |
| 数据库 | SQLite，`data/research.db`（相对启动目录） |
| 前端 | 原生 HTML/CSS/JS，模板在 `templates/`，脚本在 `static/js/` |
| 构建 | Maven，依赖走全局仓库 `D:\ownerMaven\mavenRepository` |

## 3. 常用命令

```powershell
# 编译（必须加 -q，避免刷屏）
mvn -q -DskipTests compile

# 打包
mvn -q -DskipTests package

# 启动：双击 start.bat（脚本内已设定 JAVA_HOME 与 PATH）
# 或命令行：
mvn spring-boot:run
```

- 端口固定 **8088**，改动配置后必须重启服务才生效。
- 检查端口占用：`Get-NetTCPConnection -LocalPort 8088 -State Listen`
- 日志控制台编码为 GBK（配合 cmd 窗口），IDEA 内看日志乱码属预期取舍。
- 环境变量 `SEARCH_API_KEY`、`DEEPSEEK_API_KEY`、`BOCHA_API_KEY` 需在启动前就绪；TRAE 终端内可能拿到旧快照，必要时在命令中显式注入。

## 4. 代码地图

```
controller/
  PageController        /  /tasks  /monitor  /monitor/{taskId}
                        /monitor 不带 id 时自动选“运行中优先，否则最新”的任务
  ChatController        会话 CRUD、消息发送、意图路由、SSE 流式回复
  ResearchController    任务提交/查询、暂停/继续/终止、报告下载
  MonitorController     监控页 SSE 事件流
service/
  ResearchLoop          核心编排：多轮搜索 → 起草 → 审稿修订 → 落盘；含任务控制检查点
  LlmClient             DeepSeek 同步/流式调用（思考回调可能为 null，需空判断）
  PromptBuilder         全部提示词外置，改提示词只改这里
  SearchClient          搜索接口；DoubaoSearchClient 为主，BochaSearchClient 为备
  ResultProcessor       过滤、去重、聚类（标题相似度 >0.85 只留高分）、留存判定
  ReviewService         单次审稿 + 单次修订
  ReportWriter          报告写文件（失败时降级到 data/reports）
  EventPublisher        监控页 SSE 事件
  TaskStore             任务与报告的 SQLite 读写
  ConversationStore     会话与消息的 SQLite 读写
config/
  DbConfig / DbInitializer / AgentProperties / SearchClientConfig / LangChainConfig
model/   SearchModels  ReviewModels
```

## 5. 核心流程与判定规则

### 5.1 调研循环（ResearchLoop）

动态轮次，不是固定次数。每轮两拍：

1. **策略拍**：把当前资料池 + 已搜词交给模型做缺口分析，产出下一批搜索词、平台、理由。
2. **执行拍**：`CompletableFuture.allOf` 并发搜索（并发上限 5）→ 过滤 → 合并 → 得到本轮净增条数 `newFindings`。

停止优先级：

1. 模型判定 `wantReport() == true`（信息已充分，正常收口）
2. 模型给不出新的有效搜索词（视为足够）
3. 护栏兜底：`newFindings < 1`（边际收益不足）、轮次上限 8、累计搜索上限 30、超时 20 分钟、用户取消

### 5.2 审稿（ReviewService）

固定「审稿 → 修订」单次流程，不循环：

- 合格 = `needRevise()` 为 false，或 `issues` 为空。
- 需修订且存在 B 类「原料缺口」→ 先补搜（最多 2 个 query），再由模型重写一次，即为终稿，不再复核。
- A 类为写作缺陷（无依据断言、信息缺失、引用不匹配），B 类为资料缺口。

### 5.3 意图路由（ChatController）

消息含「调研 / 分析 / 对比」等关键词 → 创建调研任务并返回任务卡片（前端 5 秒轮询状态）；否则走 DeepSeek 流式闲聊。改动路由规则时，仅调整该控制器内的判定方法。

## 6. 修改约定

- 提示词一律放 `PromptBuilder`，不要散落在业务代码里。
- 新增可观测步骤时，通过 `EventPublisher` 发事件，监控页据此渲染节点，不要绕过事件机制直接写库。
- 搜索必须走 `SearchClient` 接口，便于 provider 切换。
- 新增前端交互：切换会话等并发请求用 `Promise.all` 并行，避免串行白屏。
- 监控页 SVG 节点点击使用事件委托（`pointerdown`），因为 SSE 会高频重建 DOM。
- 任务状态渲染先用缓存套用终态，不要等首个轮询周期。

## 7. 验证方式（完成即验证，不要口头声称通过）

- **后端**：`mvn -q -DskipTests compile` 通过；启动后 `curl.exe -s -o NUL -w "%{http_code}" http://localhost:8088/` 返回 200。
- **前端**：浏览器打开 `http://localhost:8088`、`/monitor`、`/tasks`，检查控制台无报错；改动静态资源后强刷（Ctrl+F5）。
- **功能**：发起一次「对比 A 与 B」类调研，确认任务卡片状态推进、监控页出现轮次节点、报告可下载。
- Maven / 长耗时命令把输出重定向到文件再看尾部，不要在终端整段缓冲。

## 8. 禁止事项

- 不要把 API Key 明文写入配置文件；统一用环境变量。
- 不要把 `.bat` / `.cmd` 写成含中文或全角符号（必须是纯 ASCII）。
- 不要删改既有的失败兜底逻辑（补搜、降级目录、空回调判断）。
- 不要在单个请求链路里串行执行本可并行的 IO。
- 不要随意提升护栏上限（8 轮 / 30 次 / 20 分钟）来“解决”搜索不足，应先查策略提示词与过滤阈值。

## 9. 已知陷阱

- `start.bat` 若含中文会出现 `'xxx' 不是内部或外部命令`：cmd 按 GBK 解析字节，UTF-8 中文会破坏 `if (...)` 块。交付前验字节，非 ASCII 计数应为 0。
- `LlmClient` 思考回调为 null 时曾 NPE，闲聊链路全挂。
- TRAE 沙箱对 F 盘写入有限制；服务在 TRAE 外启动则正常。
- SQLite 路径相对启动目录，务必从项目根目录启动。
