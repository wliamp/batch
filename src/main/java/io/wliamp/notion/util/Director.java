package io.wliamp.notion.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.file.Files.*;
import static reactor.core.publisher.Mono.*;

public class Director {
    private static final Logger log = LoggerFactory.getLogger(Director.class);

    public static Mono<Void> createDir(Path dir) {
        return fromRunnable(() -> {
            try {
                createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public static Mono<Void> writeFiles(ObjectMapper mapper,
                                        Path dir,
                                        JsonNode obj,
                                        java.util.List<JsonNode> blocks) {
        return fromRunnable(() -> {
            try {
                writeString(
                        dir.resolve("page.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
                writeString(
                        dir.resolve("blocks.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public static Mono<Void> checkAndDelete(ObjectMapper mapper, Path dir, Set<String> activeIds) {
        var pageJson = dir.resolve("page.json");
        return exists(pageJson) ? fromCallable(() -> mapper.readTree(readString(pageJson)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(root -> root.get("id").asText().replace("-", ""))
                .flatMap(id -> {
                    if (activeIds.contains(id)) return empty();
                    log.info("🗑 Removing deleted page: {} (id={})", dir, id);
                    return deleteRecursively(dir);
                })
                .onErrorResume(ex -> {
                    log.warn("⚠ Could not parse {}: {}", pageJson, ex.toString());
                    return empty();
                }) : empty();
    }

    private static Mono<Void> deleteRecursively(Path path) {
        Mono<Void> voidMono = fromCallable(() -> {
            try (Stream<Path> walker = walk(path)) {
                walker.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                deleteIfExists(p);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to delete: " + p, e);
                            }
                        });
            }
            return null;
        });
        voidMono
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.warn("⚠ Could not delete {}", path, e))
                .onErrorResume(_ -> empty());
        return voidMono;
    }
}
