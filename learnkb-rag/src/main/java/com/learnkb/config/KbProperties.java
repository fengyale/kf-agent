package com.learnkb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "learnkb")
public class KbProperties {

    private String corpusDir;
    private String storeFile;
    private int topK = 5;
    private int chunkSize = 400;
    private int chunkOverlap = 40;
    private final Embedding embedding = new Embedding();
    private final Chat chat = new Chat();

    public String getCorpusDir() {
        return corpusDir;
    }

    public void setCorpusDir(String corpusDir) {
        this.corpusDir = corpusDir;
    }

    public String getStoreFile() {
        return storeFile;
    }

    public void setStoreFile(String storeFile) {
        this.storeFile = storeFile;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Chat getChat() {
        return chat;
    }

    public static class Embedding {
        private String provider = "ollama";
        private final Ollama ollama = new Ollama();
        private final OpenaiCompatible openaiCompatible = new OpenaiCompatible();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public Ollama getOllama() {
            return ollama;
        }

        public OpenaiCompatible getOpenaiCompatible() {
            return openaiCompatible;
        }

        public static class Ollama {
            private String baseUrl = "http://localhost:11434";
            private String model = "bge-m3";

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }
        }

        public static class OpenaiCompatible {
            private String baseUrl;
            private String apiKey;
            private String model;

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }
        }
    }

    public static class Chat {
        private String provider = "openai-compatible";
        private final OpenaiCompatible openaiCompatible = new OpenaiCompatible();
        private final Ollama ollama = new Ollama();
        private final Deepseek deepseek = new Deepseek();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public OpenaiCompatible getOpenaiCompatible() {
            return openaiCompatible;
        }

        public Ollama getOllama() {
            return ollama;
        }

        public Deepseek getDeepseek() {
            return deepseek;
        }

        public static class Deepseek {
            private String baseUrl = "https://api.deepseek.com";
            private String apiKey;
            private String model = "deepseek-chat";
            private double temperature = 0.3;

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }

            public double getTemperature() {
                return temperature;
            }

            public void setTemperature(double temperature) {
                this.temperature = temperature;
            }
        }

        public static class OpenaiCompatible {
            private String baseUrl;
            private String apiKey;
            private String model;
            private double temperature = 0.3;

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getApiKey() {
                return apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }

            public double getTemperature() {
                return temperature;
            }

            public void setTemperature(double temperature) {
                this.temperature = temperature;
            }
        }

        public static class Ollama {
            private String baseUrl = "http://localhost:11434";
            private String model = "qwen2.5:3b";
            private double temperature = 0.3;

            public String getBaseUrl() {
                return baseUrl;
            }

            public void setBaseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
            }

            public String getModel() {
                return model;
            }

            public void setModel(String model) {
                this.model = model;
            }

            public double getTemperature() {
                return temperature;
            }

            public void setTemperature(double temperature) {
                this.temperature = temperature;
            }
        }
    }
}
