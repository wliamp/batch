package io.wliamp.notion.compo;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
public class EnvConfig {
    @Value("${DIRECTORY_TEMP}")
    private String tmp;

    @Value("${NOTION_INTEGRATION_TOKEN}")
    private String token;
}
