package com.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 配置结构照搬 research-agent 五段式：search.doubao/bocha、llm.deepseek/openai-compatible/ollama、
 * budget、report.outputDir；决策 Agent 新增 data 段（主库 + 调研库只读引用路径）与 llm 录放开关。
 */
@ConfigurationProperties("agent")
public class AgentProperties {

    private Search search = new Search();
    private Budget budget = new Budget();
    private Llm llm = new Llm();
    private Report report = new Report();
    private Data data = new Data();

    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }
    public Budget getBudget() { return budget; }
    public void setBudget(Budget budget) { this.budget = budget; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Report getReport() { return report; }
    public void setReport(Report report) { this.report = report; }
    public Data getData() { return data; }
    public void setData(Data data) { this.data = data; }

    public static class Search {
        private String provider = "doubao";
        private Doubao doubao = new Doubao();
        private Bocha bocha = new Bocha();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public Doubao getDoubao() { return doubao; }
        public void setDoubao(Doubao doubao) { this.doubao = doubao; }
        public Bocha getBocha() { return bocha; }
        public void setBocha(Bocha bocha) { this.bocha = bocha; }

        public static class Doubao {
            private String apiKey = "";
            private Duration timeout = Duration.ofSeconds(30);

            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public Duration getTimeout() { return timeout; }
            public void setTimeout(Duration timeout) { this.timeout = timeout; }
        }

        public static class Bocha {
            private String apiKey = "";

            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        }
    }

    public static class Budget {
        // 单次分析成本护栏（硬顶）：超限被拦截，超时输出已完成部分。
        // 日常事务走轻量路径天然远低于此值，重大决策全流程才会逼近上限。
        private int maxModelCalls = 8;
        // 深度路径（deep）独立额度：前置分析步 6 次 + 最多 3 轮质疑 + 2 次修订 = 11 次，
        // 再留 1 次给"解析失败重试"（重试同样计入预算），故默认 12；仅对 depth=deep 生效。
        private int deepMaxModelCalls = 12;
        private int maxDurationMinutes = 3;
        private int maxSearchesPerAnalysis = 3;
        private int countPerSearch = 5;

        public int getMaxModelCalls() { return maxModelCalls; }
        public void setMaxModelCalls(int maxModelCalls) { this.maxModelCalls = maxModelCalls; }
        public int getDeepMaxModelCalls() { return deepMaxModelCalls; }
        public void setDeepMaxModelCalls(int deepMaxModelCalls) { this.deepMaxModelCalls = deepMaxModelCalls; }
        public int getMaxDurationMinutes() { return maxDurationMinutes; }
        public void setMaxDurationMinutes(int maxDurationMinutes) { this.maxDurationMinutes = maxDurationMinutes; }
        public int getMaxSearchesPerAnalysis() { return maxSearchesPerAnalysis; }
        public void setMaxSearchesPerAnalysis(int maxSearchesPerAnalysis) { this.maxSearchesPerAnalysis = maxSearchesPerAnalysis; }
        public int getCountPerSearch() { return countPerSearch; }
        public void setCountPerSearch(int countPerSearch) { this.countPerSearch = countPerSearch; }
    }

    public static class Llm {
        private String provider = "deepseek";
        private Deepseek deepseek = new Deepseek();
        private OpenaiCompatible openaiCompatible = new OpenaiCompatible();
        private Ollama ollama = new Ollama();
        // 测试录放：录制模式存请求/响应文件；回放模式读录制不真调
        private boolean recordMode = false;
        private boolean replayMode = false;
        private String recordingsDir = "data/recordings";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public Deepseek getDeepseek() { return deepseek; }
        public void setDeepseek(Deepseek deepseek) { this.deepseek = deepseek; }
        public OpenaiCompatible getOpenaiCompatible() { return openaiCompatible; }
        public void setOpenaiCompatible(OpenaiCompatible openaiCompatible) { this.openaiCompatible = openaiCompatible; }
        public Ollama getOllama() { return ollama; }
        public void setOllama(Ollama ollama) { this.ollama = ollama; }
        public boolean isRecordMode() { return recordMode; }
        public void setRecordMode(boolean recordMode) { this.recordMode = recordMode; }
        public boolean isReplayMode() { return replayMode; }
        public void setReplayMode(boolean replayMode) { this.replayMode = replayMode; }
        public String getRecordingsDir() { return recordingsDir; }
        public void setRecordingsDir(String recordingsDir) { this.recordingsDir = recordingsDir; }

        public String activeApiKey() {
            return switch (provider) {
                case "deepseek" -> deepseek.apiKey;
                case "openai-compatible" -> openaiCompatible.apiKey;
                default -> "";
            };
        }

        public String activeModel() {
            return switch (provider) {
                case "deepseek" -> deepseek.model;
                case "openai-compatible" -> openaiCompatible.model;
                case "ollama" -> ollama.model;
                default -> "";
            };
        }

        public static class Deepseek {
            private String baseUrl = "https://api.deepseek.com";
            private String apiKey = "";
            private String model = "deepseek-v4-flash";
            private double temperature = 0.3;

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
            public double getTemperature() { return temperature; }
            public void setTemperature(double temperature) { this.temperature = temperature; }
        }

        public static class OpenaiCompatible {
            private String baseUrl = "";
            private String apiKey = "";
            private String model = "";
            private double temperature = 0.3;

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
            public double getTemperature() { return temperature; }
            public void setTemperature(double temperature) { this.temperature = temperature; }
        }

        public static class Ollama {
            private String baseUrl = "http://localhost:11434";
            private String model = "qwen3.5:2b";
            private double temperature = 0.3;

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getModel() { return model; }
            public void setModel(String model) { this.model = model; }
            public double getTemperature() { return temperature; }
            public void setTemperature(double temperature) { this.temperature = temperature; }
        }
    }

    public static class Report {
        private String outputDir = "F:\\learn\\ai转型计划个人学习\\decision-agent决策分析";

        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    }

    public static class Data {
        // 决策 Agent 主库（画像/行为日志/分析历史等 9 张表）
        private String dbPath = "data/decision.db";
        // 调研 Agent 报告库，只读跨库引用
        private String researchDbPath = "D:/workspace/kf-agent/research-agent/data/research.db";
        // 调研报告 Markdown 目录（research.db 不存在/表不存在时的降级扫描源）
        private String researchMarkdownDir = "F:\\learn\\ai转型计划个人学习\\research-agent调研报告";

        public String getDbPath() { return dbPath; }
        public void setDbPath(String dbPath) { this.dbPath = dbPath; }
        public String getResearchDbPath() { return researchDbPath; }
        public void setResearchDbPath(String researchDbPath) { this.researchDbPath = researchDbPath; }
        public String getResearchMarkdownDir() { return researchMarkdownDir; }
        public void setResearchMarkdownDir(String researchMarkdownDir) { this.researchMarkdownDir = researchMarkdownDir; }
    }
}