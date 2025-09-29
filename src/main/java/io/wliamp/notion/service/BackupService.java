package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.wliamp.notion.util.Director;
import io.wliamp.notion.util.Extractor;
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
import java.util.List;
import java.util.Set;

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
            Path outDir = Files.createDirectories(Path.of("backup").resolve(workspaceName));

            Mono<List<String>> pipeline = searchAllObjects(token)
                    .flatMapSequential(obj -> backupObject(obj, outDir, token), 4)
                    .map(obj -> obj.get("id").asText().replace("-", ""))
                    .collectList()
                    .flatMap(activeIds -> cleanupDeletedObjects(outDir, new HashSet<>(activeIds))
                            .thenReturn(activeIds));

            var activeIds = pipeline.block();
            log.info("✅ Backup completed for [{}]. {} objects.",
                    workspaceName, activeIds != null ? activeIds.size() : 0);

        } catch (IOException e) {
            throw new RuntimeException("Backup failed", e);
        }
    }

    // --- Search ---
    private Flux<JsonNode> searchAllObjects(String token) {
        ObjectNode body = mapper.createObjectNode();
        body.set("sort", mapper.createObjectNode()
                .put("direction", "descending")
                .put("timestamp", "last_edited_time"));
        body.put("page_size", 100);

        return requestPost("/search", token, body)
                .flatMapMany(root -> asFlux(root.get("results")));
    }

    // --- Backup Object ---
    private Mono<JsonNode> backupObject(JsonNode obj, Path outDir, String token) {
        String id = obj.get("id").asText();
        String shortId = id.replace("-", "");
        var titleResult = Extractor.extractTitle(obj, shortId);

        Path objDir = outDir.resolve(Director.safeName(titleResult.title()));

        Mono<Void> mkdir = Director.createDir(objDir);
        Mono<List<JsonNode>> blocksMono = fetchBlockTree(shortId, token).collectList();
        Mono<Void> writeFiles = blocksMono.flatMap(blocks ->
                Director.writeFiles(mapper, objDir, obj, blocks, id, shortId, titleResult)
        );

        return mkdir.then(writeFiles).thenReturn(obj);
    }

    // --- Fetch Block Tree ---
    private Flux<JsonNode> fetchBlockTree(String parentId, String token) {
        String uri = "/blocks/" + parentId + "/children?page_size=100";
        return requestGet(uri, token).flatMapMany(root -> asFlux(root.get("results")));
    }

    // --- Cleanup Deleted ---
    private Mono<Void> cleanupDeletedObjects(Path outDir, Set<String> activeIds) {
        return Mono.fromCallable(() -> Files.list(outDir))
                .flatMapMany(Flux::fromStream)
                .filter(Files::isDirectory)
                .flatMap(path -> Director.checkAndDelete(mapper, path, activeIds), 4)
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

    private Mono<JsonNode> requestPost(String uri, String token, Object body) {
        return webClient.post()
                .uri(uri)
                .header("Authorization", "Bearer " + token)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class);
    }

    private Flux<JsonNode> asFlux(JsonNode results) {
        return (results != null && results.isArray())
                ? Flux.fromIterable(results)
                : Flux.empty();
    }
}
