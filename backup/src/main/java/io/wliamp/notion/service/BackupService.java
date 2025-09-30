package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.Director;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;

import static io.wliamp.notion.util.Safer.safeName;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Path.*;
import static reactor.core.publisher.Flux.fromIterable;

@Service
@Slf4j
@RequiredArgsConstructor
public class BackupService {
    private final SearchService searchService;
    private final FetchService fetchService;
    private final FileService fileService;
    private final RemoveService removeService;
    private final ExtractService extractService;
    private final Director director;

    public Mono<Void> backup() {
        return fromIterable(director.getGithubSecrets().entrySet())
                .flatMap(entry -> backupOneWorkspace(entry.getValue(), entry.getKey()), 4)
                .then();
    }

    private Mono<Void> backupOneWorkspace(String token, String workspace) {
        log.info("🚀 Starting backup for workspace [{}]", workspace);

        Path outDir;
        try {
            outDir = createDirectories(of("storage", workspace));
        } catch (IOException e) {
            log.error("❌ Failed to create directory for [{}]", workspace, e);
            return Mono.empty();
        }

        return searchService.search(token)
                .flatMapSequential(obj -> backupObject(obj, outDir, token), 4)
                .flatMap(extractService::safeId)
                .collectList()
                .flatMap(ids -> removeService.remove(outDir, new HashSet<>(ids)))
                .doOnError(err -> log.error("❌ Backup failed for [{}]", workspace, err))
                .doOnSuccess(v -> log.info("✅ Backup completed for [{}]", workspace));
    }

    private Mono<JsonNode> backupObject(JsonNode obj, Path outDir, String token) {
        return extractService.safeId(obj).flatMap(id ->
                extractService.extractTitle(obj, id).flatMap(titleResult -> {
                    var objDir = outDir.resolve(safeName(titleResult.name()));

                    return fetchService.fetchBlockTree(id, token)
                            .collectList()
                            .flatMap(blocks -> fileService.writeObjectFiles(objDir, obj, blocks))
                            .thenReturn(obj);
                }));
    }
}
