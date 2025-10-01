package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

import static io.wliamp.notion.compa.Utility.getDir;
import static io.wliamp.notion.compa.Utility.safeName;
import static io.wliamp.notion.constant.Constant.*;
import static reactor.core.publisher.Mono.fromRunnable;

@Service
@Slf4j
@RequiredArgsConstructor
public class BackupService {
    @Value("${NOTION_INTEGRATION_TOKEN}")
    private String token;

    private final FetchService fetchService;
    private final SearchService searchService;
    private final CommonService commonService;
    private final JsonService jsonService;
    private final PathService pathService;

    public void backup() {
        var root = getDir();
        log.info("🚀 Starting backup into repo {}", root);

        prepareRoot(root)
                .flatMapMany(this::searchAndBackupObjects)
                .collectList()
                .doOnSuccess(_ -> log.info("🎉 Backup completed successfully"))
                .doOnError(e -> log.error("🔥 Backup failed", e))
                .block();
    }

    private Mono<Path> prepareRoot(Path root) {
        return pathService.createDir(root)
                .doOnSubscribe(sub -> log.debug("📂 Preparing output directory at {}", root));
    }

    private Flux<JsonNode> searchAndBackupObjects(Path outDir) {
        return searchService.search(token)
                .doOnSubscribe(sub -> log.info("🔍 Searching objects ..."))
                .flatMapSequential(node -> backupObject(node, outDir), 4);
    }

    private Mono<JsonNode> backupObject(JsonNode node, Path outDir) {
        return commonService.safeId(node)
                .flatMap(id -> commonService.extractTitle(node)
                        .flatMap(title -> {
                            log.info("➡️ Backing up object [{}] with title [{}]", id, title.name());
                            return fetchAndWrite(id, node, outDir.resolve(safeName(title.name())));
                        })
                )
                .doOnError(e -> log.warn("⚠ Failed to backup object [{}]", node.path("id").asText(), e));
    }

    private Mono<JsonNode> fetchAndWrite(String id, JsonNode node, Path objDir) {
        return fetchService.fetch(id, token)
                .doOnSubscribe(sub -> log.debug("📥 Fetching block tree for [{}]", id))
                .collectList()
                .doOnNext(blocks -> log.debug("📦 Fetched {} blocks for [{}]", blocks.size(), id))
                .flatMap(blocks -> pathService.createDir(objDir)
                        .thenMany(
                                jsonService.create(objDir.resolve(JSON1.getJson()), node)
                                        .then(jsonService.create(objDir.resolve(JSON2.getJson()), blocks))
                        )
                        .then(fromRunnable(() ->
                                log.info("💾 Object [{}] written to {}", id, objDir)))
                )
                .doOnError(e -> log.error("❌ Failed to fetch/write object [{}]", id, e))
                .thenReturn(node);
    }
}
