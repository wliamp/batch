package io.wliamp.notion.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
public class EnvConfig {
    @Value("${tmp}")
    private String tmp;

    @Value("${NOTION_INTEGRATION_TOKEN}")
    private String token;
}
