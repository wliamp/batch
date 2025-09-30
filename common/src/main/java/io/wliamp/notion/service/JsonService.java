package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

import static java.nio.file.Files.writeString;
import static reactor.core.publisher.Mono.fromCallable;
import static reactor.core.scheduler.Schedulers.boundedElastic;

@Service
@Slf4j
@RequiredArgsConstructor
public class JsonService {
    private final ObjectMapper mapper;

    public Mono<JsonNode> read(Path path) {
        return fromCallable(() -> mapper.readTree(path.toFile()))
                .doOnError(e -> log.error("❌ JSON read() FAILED for path={}", path, e));
    }

    public Mono<Void> create(Path path, Object obj) {
        return fromCallable(() -> mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj))
                .flatMap(json -> fromCallable(() -> {
                            writeString(path, json);
                            return path;
                        })
                        .subscribeOn(boundedElastic()))
                .doOnSuccess(p -> log.info("📝 {} created", p))
                .doOnError(e -> log.error("❌ JSON create() FAILED for path={}", path, e))
                .then();
    }
}

