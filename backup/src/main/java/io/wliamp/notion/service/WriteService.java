package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static java.nio.file.Files.*;
import static reactor.core.publisher.Mono.fromRunnable;
import static reactor.core.scheduler.Schedulers.boundedElastic;

@Service
@Slf4j
@RequiredArgsConstructor
public class WriteService {
    private final ObjectMapper mapper;

    public Mono<Void> write(Path dir, JsonNode obj, List<JsonNode> blocks) {
        return fromRunnable(() -> {
            try {
                createDirectories(dir);
                log.debug("📂 Directory ensured: {}", dir);

                writeString(dir.resolve("page.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
                log.debug("📝 page.json written for {}", dir);

                writeString(dir.resolve("blocks.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks));
                log.debug("📝 blocks.json written for {}", dir);

                log.info("✅ Files written successfully for {}", dir);
            } catch (IOException e) {
                log.error("❌ Failed to write files for {}", dir, e);
            }
        }).subscribeOn(boundedElastic()).then();
    }
}

