package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.compa.Title;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.stream.Stream;

import static io.wliamp.notion.compa.Utility.extractFirstPlainText;
import static io.wliamp.notion.compa.Utility.generateCode;
import static java.util.Map.*;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.StreamSupport.stream;
import static reactor.core.publisher.Mono.fromCallable;

@Service
@Slf4j
@RequiredArgsConstructor
public class CommonService {
    private final PathService pathService;

    public Mono<String> safeId(JsonNode node) {
        return fromCallable(() -> node.get("id").asText().replace("-", ""))
                .doOnError(e -> log.error("❌ safeId() FAILED from node={}", node, e));
    }

    public Mono<Title> extractTitle(JsonNode node) {
        return fromCallable(() ->
                ofNullable(node.get("properties"))
                        .flatMap(n -> stream(n.spliterator(), false)
                                .filter(prop -> prop.has("title"))
                                .map(p -> "title-" + generateCode(10))
                                .findFirst())
                        .or(() -> extractFirstPlainText(node.get("title"))
                                .map(t -> "field-" + generateCode(10)))
                        .or(() -> of("untitled-" + generateCode(10)))
                        .map(name -> {
                            var source = Stream.of(
                                            entry("title-", "properties.title"),
                                            entry("field-", "title")
                                    )
                                    .filter(e -> name.startsWith(e.getKey()))
                                    .map(Entry::getValue)
                                    .findFirst()
                                    .orElse("fallback");
                            return new Title(name, source);
                        })
                        .orElseThrow()
        ).doOnError(e -> log.error("❌ extractTitle() FAILED from node={}", node, e));
    }

    public Mono<Boolean> isOrphan(JsonNode node, Path dir) {
        var parentId = node.path("parent_id").asText(null);
        var archived = node.path("archived").asBoolean(false);
        var parentPath = dir.resolve(ofNullable(parentId).orElse(""));

        return pathService.isExists(parentPath)
                .map(parentExists -> {
                    var orphan = !(parentId == null || parentExists) || archived;

                    log.debug("🔎 Checking orphan: parentId={}, archived={}, parentExists={}, orphan={}",
                            parentId, archived, parentExists, orphan);

                    return orphan;
                })
                .doOnError(e -> log.error("❌ isOrphan() FAILED for dir={}", dir, e));
    }
}
