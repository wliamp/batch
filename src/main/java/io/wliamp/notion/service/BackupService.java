package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wliamp.notion.util.Safer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static io.wliamp.notion.util.Extractor.*;
import static io.wliamp.notion.util.Safer.*;
import static java.nio.file.Files.*;
import static java.nio.file.Path.*;
import static reactor.core.publisher.Mono.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class BackupService {
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public void runBackup(String token, String workspaceName) {
        log.info("🚀 Starting backup for workspace [{}]", workspaceName);
        try {
            var outDir = createDirectories(of("storage").resolve(workspaceName));

            var activeIds = searchAllObjects(token)
                    .flatMapSequential(obj -> backupObject(obj, outDir, token), 4)
                    .map(Safer::safeId)
                    .collectList()
                    .flatMap(aIds -> cleanupDeletedObjects(outDir, new HashSet<>(aIds))
                            .thenReturn(aIds))
                    .doOnError(err -> log.error("❌ Backup failed for [{}]", workspaceName, err))
                    .block();

            log.info("✅ Backup completed for [{}]. {} objects.",
                    workspaceName, activeIds != null ? activeIds.size() : 0);

        } catch (IOException e) {
            log.error("❌ Backup failed due to IO error", e);
            throw new RuntimeException("Backup failed", e);
        }
    }

    // --- Search ---
    private Flux<JsonNode> searchAllObjects(String token) {
        log.debug("🔎 Searching all objects from Notion...");
        var body = mapper.createObjectNode();
        body.set("sort", mapper.createObjectNode()
                .put("direction", "descending")
                .put("timestamp", "last_edited_time"));
        body.put("page_size", 100);

        return requestPost(token, body)
                .flatMapMany(root -> {
                    var results = asFlux(root.get("results"));
                    log.info("🔎 Found {} objects", root.has("results") ? root.get("results").size() : 0);
                    return results;
                })
                .doOnError(err -> log.error("❌ Failed to search objects", err));
    }

    // --- Backup Object ---
    private Mono<JsonNode> backupObject(JsonNode obj, Path outDir, String token) {
        var id = safeId(obj);
        var title = extractTitle(obj, id).title();
        log.debug("📄 Backing up object [{}] - {}", id, title);

        var objDir = outDir.resolve(safeName(title));

        var writeFiles = fetchBlockTree(id, token)
                .collectList()
                .doOnNext(blocks -> log.debug("📦 [{}] has {} blocks", id, blocks.size()))
                .flatMap(blocks -> writeFiles(mapper, objDir, obj, blocks)
                        .doOnSuccess(_ -> log.info("💾 Wrote object [{}] to {}", id, objDir)));

        return createDir(objDir)
                .then(writeFiles)
                .thenReturn(obj);
    }

    // --- Fetch Block Tree ---
    private Flux<JsonNode> fetchBlockTree(String parentId, String token) {
        log.trace("➡️ Fetching block tree for [{}]", parentId);
        return requestGet("/blocks/" + parentId + "/children?page_size=100", token)
                .flatMapMany(root -> {
                    var results = asFlux(root.get("results"));
                    log.debug("⬅️ [{}] fetched {} child blocks",
                            parentId, root.has("results") ? root.get("results").size() : 0);
                    return results;
                })
                .doOnError(err -> log.error("❌ Failed to fetch block tree [{}]", parentId, err));
    }

    // --- Cleanup Deleted ---
    private Mono<Void> cleanupDeletedObjects(Path outDir, Set<String> activeIds) {
        log.debug("🧹 Cleaning up deleted objects in {}", outDir);
        return fromCallable(() -> list(outDir))
                .flatMapMany(Flux::fromStream)
                .filter(Files::isDirectory)
                .flatMap(path -> checkAndDelete(mapper, path, activeIds), 4)
                .then()
                .doOnSuccess(_ -> log.info("🧹 Cleanup completed"));
    }

    // --- Helpers ---
    private Mono<JsonNode> requestGet(String uri, String token) {
        log.trace("➡️ GET {}", uri);
        return webClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + mask(token))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnSuccess(resp -> log.trace("⬅️ GET {} completed", uri))
                .doOnError(err -> log.error("❌ GET {} failed", uri, err));
    }

    private Mono<JsonNode> requestPost(String token, Object body) {
        log.trace("➡️ POST /search with body {}", body);
        return webClient.post()
                .uri("/search")
                .header("Authorization", "Bearer " + mask(token))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnSuccess(resp -> log.trace("⬅️ POST /search completed"))
                .doOnError(err -> log.error("❌ POST /search failed", err));
    }

    private Flux<JsonNode> asFlux(JsonNode results) {
        return results == null || !results.isArray() ? Flux.empty() : Flux.fromIterable(results);
    }

    public static Mono<Void> createDir(Path dir) {
        return fromRunnable(() -> {
            try {
                createDirectories(dir);
                log.trace("📁 Created directory {}", dir);
            } catch (IOException e) {
                log.error("❌ Failed to create directory {}", dir, e);
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
                log.trace("💾 Wrote files for {}", dir);
            } catch (IOException e) {
                log.error("❌ Failed to write files for {}", dir, e);
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> checkAndDelete(ObjectMapper mapper, Path dir, Set<String> activeIds) {
        var pageJson = dir.resolve("page.json");
        return exists(pageJson) ? fromCallable(() -> mapper.readTree(readString(pageJson)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(root -> root.get("id").asText().replace("-", ""))
                .flatMap(id -> {
                    if (activeIds.contains(id)) {
                        return empty();
                    } else {
                        log.info("🗑️ Deleting orphaned object [{}] at {}", id, dir);
                        return deleteRecursively(dir);
                    }
                })
                .onErrorResume(err -> {
                    log.warn("⚠️ Could not check/delete dir {}", dir, err);
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
                                log.trace("🗑️ Deleted {}", p);
                            } catch (IOException e) {
                                log.error("❌ Failed to delete {}", p, e);
                                throw new RuntimeException("Failed to delete: " + p, e);
                            }
                        });
            }
            return null;
        });
        voidMono
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(err -> {
                    log.warn("⚠️ Delete failed for {}", path, err);
                    return empty();
                });
        return voidMono;
    }
}
