# AGENTS.md — learnkb-rag 项目上手说明

> 面向新加入的 AI 助手 / 开发者：读完这份就能定位代码、跑起来、知道坑在哪。
> 最后更新：2026-11-13

---

## 1. 项目定位

本地个人学习知识库的 RAG 问答助手。扫描本地 Markdown/TXT 笔记，建立向量索引后支持自然语言提问，回答附带来源文件名。

- 全程可离线运行（Ollama + 本地 embedding 模型）
- 也支持切云端大模型（DeepSeek / 豆包 / 硅基流动）提升质量
- 单用户、单机运行，**没有用户体系、没有鉴权、没有数据库**

## 2. 技术栈

| 项 | 版本 / 值 |
|---|---|
| Java | 21（`start.bat` 里硬编码 `D:\Program Files\Java\jdk-21.0.11`） |
| Spring Boot | 3.3.5 |
| LangChain4j | 1.4.0（`langchain4j` / `-ollama` / `-open-ai`） |
| Web 端口 | 8090 |
| Maven 本地仓库 | `D:\ownerMaven\mavenRepository`（`settings.xml` 指定，阿里云镜像） |

**模型分工：**

- Embedding：Ollama `quentinz/bge-base-zh-v1.5:f16`（本地，`http://127.0.0.1:11434`）
- Chat：当前 `deepseek` 通道；可切 `ollama`（本地）或 `openai-compatible`（豆包/硅基流动）

## 3. 目录结构

```
D:\workspace\learnkb-rag
├── pom.xml
├── settings.xml              # Maven：本地仓库 + 阿里云镜像
├── start.bat                 # 日常启动入口（先拉起 Ollama，再跑 jar）
├── data/
│   └── kb-store.json         # 向量库持久化文件
│   └── kb-store.json.count   # 配套的块计数文件
├── src/main/java/com/learnkb/
│   ├── LearnKbApplication.java
│   ├── config/
│   │   ├── KbProperties.java      # @ConfigurationProperties("learnkb") 全部配置绑定
│   │   ├── LangChainConfig.java   # Embedding / ChatModel / StreamingChatModel 三 Bean 装配
│   │   └── StartupConfig.java     # 启动时载入持久化向量库
│   ├── service/
│   │   ├── IngestService.java     # 扫描→切块→向量化→入库→持久化；也负责检索
│   │   └── ChatService.java       # 检索→拼 Prompt→生成；含流式实现
│   └── controller/
│       └── KbController.java      # REST + SSE 接口
└── src/main/resources/
    ├── application.yml
    └── static/index.html          # 单页前端（原生 JS，无构建）
```

## 4. 三条核心链路

### 4.1 建索引 `POST /api/ingest`

```
corpusDir 递归扫描(.md/.txt)
  → TextDocumentParser 解析
  → DocumentSplitters.recursive(chunkSize=400, chunkOverlap=40)
  → 按 batch=32 调 embedding，失败降级逐条（见 5.1）
  → InMemoryEmbeddingStore.addAll
  → serializeToFile(data/kb-store.json) + 写 .count 文件
```

**注意：这是全量重建**，每次 ingest 都新 `new InMemoryEmbeddingStore<>()` 再整体替换，不做增量。语料变多后耗时线性增长。

### 4.2 检索 `POST /api/search`

`embed(question)` → `embeddingStore.search(maxResults=topK(5), minScore=0.0)`。

`minScore=0.0` 意味着**永远能返回 5 条**，无论相关性多低。

### 4.3 问答 `POST /api/chat` 与流式 `/api/chat/stream`

```
检索 topK 片段
  → buildPrompt：把每个片段连同「来源文件名 + 相似度」拼成 context
  → RAG_PROMPT 要求：只用参考资料、不编造、不足时明说、末尾列来源
  → ChatModel.chat / StreamingChatModel.chat
```

流式为 SSE，事件名四种：`thinking` / `token` / `done` / `error`，emitter 超时 180s。

## 5. 关键实现细节（改之前必须看懂的）

### 5.1 向量化崩溃兜底 `embedBatchSafe`

bge 的 GGUF 版本遇到**个别超短文本块**会让 Ollama runner 直接崩（特定 token 序列触发，无法预先判断）。因此批量向量化失败时降级为逐条处理，定位并丢弃坏块，保住其余内容。**不要为了"简洁"删掉这段降级逻辑。**

### 5.2 持久化双文件

`InMemoryEmbeddingStore.serializeToFile()` 只存向量，不存块数。所以额外写了 `kb-store.json.count` 记录 segmentCount。

载入时若 `.count` 读不到 → `segmentCount = -1` → 最终 `isIndexed()=false`，表现为"索引丢失"。**两个文件必须配对存在**，删一个等于废掉索引。

### 5.3 流式中断保留部分结果

`onError` 里若已收到部分 token，仍然连同错误标记一起返回给前端，不丢弃。这是有意的降级策略。

### 5.4 对话模型三通道

`LangChainConfig` 用 `switch` 装配三套 Bean，`chat.provider` 取值：`ollama` / `deepseek` / 其它走 `openai-compatible` 默认分支。`ReturnThinking(true)` 目前只在 deepseek 分支启用。

## 6. 配置要点（application.yml）

| 配置 | 当前值 | 说明 |
|---|---|---|
| `learnkb.corpus-dir` | `F:\learn\ai转型计划个人学习` | 语料根目录，不存在会抛异常 |
| `learnkb.store-file` | `data/kb-store.json` | 相对启动目录 |
| `learnkb.top-k` | 5 | 检索条数 |
| `learnkb.chunk-size` / `chunk-overlap` | 400 / 40 | 改了必须全量重建索引 |
| `learnkb.embedding.provider` | `ollama` | 换 provider 后向量维度可能变化，**必须重建索引** |
| `learnkb.chat.provider` | `deepseek` | 影响 `LangChainConfig` 分支 |

**改 application.yml 后必须重启进程**，项目没有配置热加载。

## 7. 构建与运行

```bash
# 打包
mvn -s settings.xml clean package

# 日常启动（推荐，会自动检查并拉起 Ollama）
start.bat

# 或直接跑 jar
"D:\Program Files\Java\jdk-21.0.11\bin\java.exe" -jar target\learnkb-rag-1.0.0.jar
```

开发期也可以 `mvn spring-boot:run`，但要自己保证 Ollama 已在 11434 端口就绪。

## 8. 已知问题

| 问题 | 影响 | 状态 |
|---|---|---|
| `application.yml` 中 DeepSeek API Key 为明文 | 有泄露风险 | **待修**，应改读环境变量 |
| `chat.deepseek.model` 配的是 `deepseek-flash` | DeepSeek 官方常见模型名是 `deepseek-chat` / `deepseek-reasoner`，此值能否调用待验证 | **待确认** |
| `minScore=0.0` | 问知识库无关的问题也会强行拿 5 条片段作答，产生牵强回答 | 已知取舍，如需修复应提高阈值或加相关性拦截 |
| 全量重建索引 | 语料增多后 ingest 耗时长，且期间无进度反馈 | 未优化 |
| 语料目录在 F 盘 | TRAE 沙箱内可能无写权限，操作语料文件需在沙箱外执行 | 环境限制 |

## 9. 改代码前的自检清单

- [ ] 动了 `chunk-size` / `chunk-overlap` / embedding provider？→ 必须提示用户重建索引
- [ ] 动了 `data/kb-store.json` 相关逻辑？→ 确认 `.count` 文件同步处理
- [ ] 加了新配置项？→ `KbProperties` 加字段 + `application.yml` 加默认值，两处都要动
- [ ] 改了 SSE 事件名？→ 同步改 `static/index.html` 里的监听逻辑
- [ ] 新增 Java 文件？→ 确认包名在 `com.learnkb` 下，否则组件扫描不到
- [ ] 改了 `application.yml`？→ 提醒用户重启才生效
