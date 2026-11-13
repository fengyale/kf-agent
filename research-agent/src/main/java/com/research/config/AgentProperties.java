package com.research.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("agent")
public class AgentProperties {

    private Search search = new Search();
    private Budget budget = new Budget();
    private Llm llm = new Llm();
    private Report report = new Report();

    public Search getSearch() { return search; }
    public void setSearch(Search search) { this.search = search; }
    public Budget getBudget() { return budget; }
    public void setBudget(Budget budget) { this.budget = budget; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public Report getReport() { return report; }
    public void setReport(Report report) { this.report = report; }

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
        // 注意：这些是"兜底保险丝"而非"常规日程表"。轮次与搜索次数由模型每轮自主判断（enough 字段），
        // 护栏仅在模型失控（幻觉性不满足/死循环/费用失控）时强制止损。
        private int maxRounds = 8;
        private int maxSearchesPerRound = 6;
        private int maxTotalSearches = 30;
        private int minNewFindingsPerRound = 1;
        private int countPerSearch = 10;
        private int timeoutMinutes = 20;

        public int getMaxRounds() { return maxRounds; }
        public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
        public int getMaxSearchesPerRound() { return maxSearchesPerRound; }
        public void setMaxSearchesPerRound(int maxSearchesPerRound) { this.maxSearchesPerRound = maxSearchesPerRound; }
        public int getMaxTotalSearches() { return maxTotalSearches; }
        public void setMaxTotalSearches(int maxTotalSearches) { this.maxTotalSearches = maxTotalSearches; }
        public int getMinNewFindingsPerRound() { return minNewFindingsPerRound; }
        public void setMinNewFindingsPerRound(int minNewFindingsPerRound) { this.minNewFindingsPerRound = minNewFindingsPerRound; }
        public int getCountPerSearch() { return countPerSearch; }
        public void setCountPerSearch(int countPerSearch) { this.countPerSearch = countPerSearch; }
        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
    }

    public static class Llm {
        private String provider = "deepseek";
        private Deepseek deepseek = new Deepseek();
        private OpenaiCompatible openaiCompatible = new OpenaiCompatible();
        private Ollama ollama = new Ollama();

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public Deepseek getDeepseek() { return deepseek; }
        public void setDeepseek(Deepseek deepseek) { this.deepseek = deepseek; }
        public OpenaiCompatible getOpenaiCompatible() { return openaiCompatible; }
        public void setOpenaiCompatible(OpenaiCompatible openaiCompatible) { this.openaiCompatible = openaiCompatible; }
        public Ollama getOllama() { return ollama; }
        public void setOllama(Ollama ollama) { this.ollama = ollama; }

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
        private String outputDir = "F:\\learn\\ai转型计划个人学习\\research-agent调研报告";

        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    }
}
