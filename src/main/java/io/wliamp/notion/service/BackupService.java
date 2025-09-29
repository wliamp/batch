package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.wliamp.notion.util.Safer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static io.wliamp.notion.util.Director.*;
import static io.wliamp.notion.util.Extractor.*;
import static io.wliamp.notion.util.Safer.*;
import static io.wliamp.notion.util.Safer.safeName;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.list;
import static java.nio.file.Path.*;
import static reactor.core.publisher.Mono.*;

@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public BackupService(WebClient notionWebClient) {
        this.webClient = notionWebClient;
    }

    public void runBackup(String token, String workspaceName) {
        try {
            var outDir = createDirectories(of("storage").resolve(workspaceName));

            var activeIds = searchAllObjects(token)
                    .flatMapSequential(obj -> backupObject(obj, outDir, token), 4)
                    .map(Safer::safeId)
                    .collectList()
                    .flatMap(aIds -> cleanupDeletedObjects(outDir, new HashSet<>(aIds))
                            .thenReturn(aIds)).block();
            log.info("✅ Backup completed for [{}]. {} objects.",
                    workspaceName, activeIds != null ? activeIds.size() : 0);

        } catch (IOException e) {
            throw new RuntimeException("Backup failed", e);
        }
    }

    // --- Search ---
    private Flux<JsonNode> searchAllObjects(String token) {
        var body = mapper.createObjectNode();
        body.set("sort", mapper.createObjectNode()
                .put("direction", "descending")
                .put("timestamp", "last_edited_time"));
        body.put("page_size", 100);

        return requestPost(token, body)
                .flatMapMany(root -> asFlux(root.get("results")));
    }

    // --- Backup Object ---
    private Mono<JsonNode> backupObject(JsonNode obj, Path outDir, String token) {
        var id = safeId(obj);

        var objDir = outDir.resolve(safeName(extractTitle(obj, id).title()));

        var writeFiles = fetchBlockTree(id, token)
                .collectList()
                .flatMap(blocks ->
                        writeFiles(mapper, objDir, obj, blocks)
                );

        return createDir(objDir)
                .then(writeFiles)
                .thenReturn(obj);
    }

    // --- Fetch Block Tree ---
    private Flux<JsonNode> fetchBlockTree(String parentId, String token) {
        return requestGet("/blocks/" + parentId + "/children?page_size=100", token)
                .flatMapMany(root -> asFlux(root.get("results")));
    }

    // --- Cleanup Deleted ---
    private Mono<Void> cleanupDeletedObjects(Path outDir, Set<String> activeIds) {
        return fromCallable(() -> list(outDir))
                .flatMapMany(Flux::fromStream)
                .filter(Files::isDirectory)
                .flatMap(path -> checkAndDelete(mapper, path, activeIds), 4)
                .then();
    }

    // --- Helpers ---
    private Mono<JsonNode> requestGet(String uri, String token) {
        return webClient.get()
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    private Mono<JsonNode> requestPost(String token, Object body) {
        return webClient.post()
                .uri("/search")
                .header("Authorization", "Bearer " + token)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    private Flux<JsonNode> asFlux(JsonNode results) {
        return results == null || !results.isArray() ? Flux.empty() : Flux.fromIterable(results);
    }
}
