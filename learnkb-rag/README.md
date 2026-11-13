# 学习知识库问答（learnkb-rag）

基于 Spring Boot 3 + LangChain4j 的本地 RAG 问答助手，扫描 `F:\learn\ai转型计划个人学习` 下的 md/txt 笔记，建向量索引后可自然语言提问，回答附带来源文件。

## 日常使用（不需要 IDEA）

1. 双击 `start.bat`
2. 浏览器打开 http://localhost:8090
3. 笔记有更新时点页面上的「建立/重建索引」

依赖：本机 Ollama 已安装（脚本会自动拉起服务），模型用本地已有的 `bge-base-zh-v1.5`（Embedding）和 `qwen3.5:2b`（对话），全部离线运行，无需 API Key。

## 接口

| 接口 | 说明 |
|---|---|
| `POST /api/ingest` | 扫描语料目录重建索引（持久化到 data/kb-store.json） |
| `POST /api/chat` | 问答，body: `{"question": "..."}` |
| `POST /api/search` | 只检索不生成，返回命中片段 |
| `GET /api/status` | 索引状态 |

## 配置切换（application.yml）

- 对话模型换云端豆包：`learnkb.chat.provider` 改 `openai-compatible`，填 `api-key`（火山方舟），速度和质量都会明显提升
- 语料目录：`learnkb.corpus-dir`
- 切块/检索参数：`chunk-size`、`top-k` 等

## 开发

```
mvn -s settings.xml package   # 编译打包（settings.xml 指向项目内仓库+阿里云镜像）
```

代码结构：

```
src/main/java/com/learnkb/
├── LearnKbApplication.java   # 入口
├── config/
│   ├── KbProperties.java     # 配置绑定
│   ├── LangChainConfig.java  # Embedding/Chat 模型装配（ollama/openai 双通道）
│   └── StartupConfig.java    # 启动时载入持久化向量库
├── service/
│   ├── IngestService.java    # 扫描→切块→向量化→入库→持久化
│   └── ChatService.java      # 检索→拼提示词→生成→来源
└── controller/KbController.java  # REST 接口
```
