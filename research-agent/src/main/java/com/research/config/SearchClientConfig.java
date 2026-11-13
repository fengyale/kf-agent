package com.research.config;

import com.research.service.BochaSearchClient;
import com.research.service.DoubaoSearchClient;
import com.research.service.SearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SearchClientConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchClientConfig.class);

    @Bean
    public SearchClient searchClient(AgentProperties props) {
        String provider = props.getSearch().getProvider();
        log.info("搜索 Provider: {}", provider);
        return switch (provider) {
            case "bocha" -> new BochaSearchClient(props);
            default -> new DoubaoSearchClient(props);
        };
    }
}
