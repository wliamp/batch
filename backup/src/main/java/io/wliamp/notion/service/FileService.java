package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static java.nio.file.Files.*;
import static reactor.core.publisher.Mono.fromRunnable;
import static reactor.core.scheduler.Schedulers.boundedElastic;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileService {
    private final ObjectMapper mapper;

    public Mono<Void> writeObjectFiles(Path dir, JsonNode obj, List<JsonNode> blocks) {
        return fromRunnable(() -> {
            try {
                createDirectories(dir);
                writeString(dir.resolve("page.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
                writeString(dir.resolve("blocks.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks));
            } catch (IOException e) {
                log.error("❌ Failed to write files for {}", dir, e);
            }
        }).subscribeOn(boundedElastic()).then();
    }

    public Mono<Void> deleteRecursively(Path path) {
        return fromRunnable(() -> {
            try (var walker = walk(path)) {
                walker.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { deleteIfExists(p); }
                    catch (IOException e) { log.warn("⚠️ Failed to delete {}", p, e); }
                });
            } catch (IOException e) {
                log.error("❌ Failed to walk path {}", path, e);
            }
        }).subscribeOn(boundedElastic()).then();
    }
}

