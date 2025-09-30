package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.CommonService;
import io.wliamp.notion.Director;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;

import static io.wliamp.notion.Utility.*;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Path.*;
import static reactor.core.publisher.Flux.fromIterable;

@Service
@Slf4j
@RequiredArgsConstructor
public class BackupService {
    private final SearchService searchService;
    private final FetchService fetchService;
    private final WriteService writeService;
    private final RemoveService removeService;
    private final Director director;
    private final CommonService commonService;

    public Mono<Void> backup() {
        log.info("🚀 Starting global backup for {} workspaces", director.getGithubSecrets().size());
        return fromIterable(director.getGithubSecrets().entrySet())
                .flatMap(entry -> backupWorkspace(entry.getValue(), entry.getKey()), 4)
                .then()
                .doOnSuccess(v -> log.info("🎉 Global backup completed successfully"))
                .doOnError(e -> log.error("🔥 Global backup failed", e));
    }

    private Mono<Void> backupWorkspace(String token, String workspace) {
        log.info("🚀 Starting backup for workspace [{}]", workspace);

        Path outDir;
        try {
            outDir = createDirectories(of("storage", workspace));
            log.debug("📂 Output directory created for [{}]: {}", workspace, outDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("❌ Failed to create directory for [{}]", workspace, e);
            return Mono.empty();
        }

        return searchService.search(token)
                .doOnSubscribe(sub -> log.info("🔍 Searching objects in workspace [{}]", workspace))
                .flatMapSequential(obj -> backupNode(obj, outDir, token), 4)
                .flatMap(commonService::safeId)
                .collectList()
                .flatMap(ids -> {
                    log.debug("🧹 Cleaning up unused files in [{}], keeping {} objects", workspace, ids.size());
                    return removeService.remove(outDir, new HashSet<>(ids));
                })
                .doOnError(err -> log.error("❌ Backup failed for [{}]", workspace, err))
                .doOnSuccess(v -> log.info("✅ Backup completed for [{}]", workspace));
    }

    private Mono<JsonNode> backupNode(JsonNode obj, Path outDir, String token) {
        return commonService.safeId(obj).flatMap(id -> {
            log.debug("📄 Processing object [{}]", id);
            return commonService.extractTitle(obj).flatMap(title -> {
                var objDir = outDir.resolve(safeName(title.name()));
                log.info("➡️ Backing up object [{}] with title [{}]", id, title.name());

                return fetchService.fetch(id, token)
                        .doOnSubscribe(sub -> log.debug("📥 Fetching block tree for [{}]", id))
                        .collectList()
                        .doOnNext(blocks -> log.debug("📦 Fetched {} blocks for [{}]", blocks.size(), id))
                        .flatMap(blocks -> writeService.write(objDir, obj, blocks)
                                .doOnSuccess(v -> log.info("💾 Object [{}] written to {}", id, objDir)))
                        .thenReturn(obj);
            });
        });
    }
}
