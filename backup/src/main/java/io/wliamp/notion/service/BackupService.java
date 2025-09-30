package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.compo.Director;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

import static io.wliamp.notion.compa.Utility.*;
import static reactor.core.publisher.Flux.fromIterable;
import static reactor.core.publisher.Mono.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class BackupService {
    private final Director director;

    private final FetchService fetchService;
    private final RemoveService removeService;
    private final SearchService searchService;

    private final CommonService commonService;
    private final JsonService jsonService;
    private final PathService pathService;

    public Mono<Void> backup() {
        log.info("🚀 Starting global backup for {} workspaces", director.getGithubSecrets().size());
        return fromIterable(director.getGithubSecrets().entrySet())
                .flatMap(entry -> backupWorkspace(entry.getValue(), entry.getKey()), 4)
                .then()
                .doOnSuccess(v -> log.info("🎉 Global Backup completed successfully"))
                .doOnError(e -> log.error("🔥 Global Backup Failed", e));
    }

    private Mono<Void> backupWorkspace(String token, String workspace) {
        log.info("🚀 Starting backup for workspace [{}]", workspace);

        var outPath = Paths.get("storage", workspace);

        return pathService.createDir(outPath)
                .doOnSubscribe(sub -> log.info("📂 Preparing output directory for [{}]", workspace))
                .flatMap(outDir -> searchService.search(token)
                        .doOnSubscribe(sub -> log.info("🔍 Searching objects in workspace [{}]", workspace))
                        .flatMapSequential(obj -> backupNode(obj, outDir, token), 4)
                        .flatMap(commonService::safeId)
                        .collectList()
                        .flatMap(ids -> {
                            log.info("🧹 Cleaning up unused files in [{}], keeping {} objects", workspace, ids.size());
                            return removeService.remove(outDir, new HashSet<>(ids));
                        })
                )
                .doOnError(e -> log.error("❌ Backup failed for [{}]", workspace, e))
                .doOnSuccess(_ -> log.info("✅ Backup completed for [{}]", workspace));
    }

    private Mono<JsonNode> backupNode(JsonNode node, Path outDir, String token) {
        return commonService.safeId(node).flatMap(id -> {
            log.info("📄 Processing object [{}]", id);
            return commonService.extractTitle(node).flatMap(title -> {
                var objDir = outDir.resolve(safeName(title.name()));
                log.info("➡️ Backing up object [{}] with title [{}]", id, title.name());

                return fetchService.fetch(id, token)
                        .doOnSubscribe(sub -> log.info("📥 Fetching block tree for [{}]", id))
                        .collectList()
                        .doOnNext(blocks -> log.info("📦 Fetched {} blocks for [{}]", blocks.size(), id))
                        .flatMap(blocks ->
                                pathService.createDir(objDir)
                                        .then(jsonService.create(objDir.resolve("meta.json"), node))
                                        .then(jsonService.create(objDir.resolve("blocks.json"), blocks))
                                        .then(fromRunnable(() -> log.info("💾 Object [{}] written to {}", id, objDir)))
                        )
                        .thenReturn(node);
            });
        });
    }
}
