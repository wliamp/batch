package io.wliamp.notion.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.wliamp.notion.record.TitleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Stream;

public class Director {
    private static final Logger log = LoggerFactory.getLogger(Director.class);

    public static Mono<Void> createDir(Path dir) {
        return Mono.fromRunnable(() -> {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public static Mono<Void> writeFiles(ObjectMapper mapper, Path dir, JsonNode obj,
                                        java.util.List<JsonNode> blocks, String id, String shortId,
                                        TitleResult titleResult) {
        return Mono.fromRunnable(() -> {
            try {
                Files.writeString(dir.resolve("page.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
                Files.writeString(dir.resolve("blocks.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks));

                ObjectNode meta = mapper.createObjectNode();
                meta.put("id", id);
                meta.put("shortId", shortId);
                meta.put("title", titleResult.title());
                meta.put("backup_time", Instant.now().toString());
                meta.put("title_source", titleResult.source());

                Files.writeString(dir.resolve("meta.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public static Mono<Void> checkAndDelete(ObjectMapper mapper, Path dir, Set<String> activeIds) {
        Path pageJson = dir.resolve("page.json");
        if (!Files.exists(pageJson)) return Mono.empty();

        return Mono.fromCallable(() -> mapper.readTree(Files.readString(pageJson)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(root -> root.get("id").asText().replace("-", ""))
                .flatMap(id -> {
                    if (!activeIds.contains(id)) {
                        log.info("🗑 Removing deleted page: {} (id={})", dir, id);
                        return deleteRecursively(dir);
                    }
                    return Mono.empty();
                })
                .onErrorResume(ex -> {
                    log.warn("⚠ Could not parse {}: {}", pageJson, ex.toString());
                    return Mono.empty();
                });
    }

    private static Mono<Void> deleteRecursively(Path path) {
        return Mono.fromCallable(() -> {
                    try (Stream<Path> walker = Files.walk(path)) {
                        walker.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException e) {
                                        throw new RuntimeException("Failed to delete: " + p, e);
                                    }
                                });
                    }
                    return (Void) null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.warn("⚠ Could not delete {}", path, e))
                .onErrorResume(e -> Mono.empty());
    }

    public static String safeName(String input) {
        if (input == null) return "untitled";
        return input.replaceAll("[^a-zA-Z0-9-_.]", "_");
    }
}
