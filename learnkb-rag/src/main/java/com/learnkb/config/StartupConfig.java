package com.learnkb.config;

import com.learnkb.service.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StartupConfig {

    private static final Logger log = LoggerFactory.getLogger(StartupConfig.class);

    @Bean
    public ApplicationRunner storeLoader(IngestService ingestService) {
        return args -> {
            try {
                ingestService.loadPersisted();
            } catch (Exception e) {
                log.warn("启动时载入向量库失败（可手动 POST /api/ingest 重建）: {}", e.getMessage());
            }
        };
    }
}
