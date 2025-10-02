package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.wliamp.notion.compa.Title;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

import static io.wliamp.notion.constant.Constant.*;
import static java.util.Optional.ofNullable;
import static java.util.stream.StreamSupport.stream;
import static reactor.core.publisher.Mono.*;
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
        return defer(() -> fromCallable(() -> {
                    var props = node.get("properties");
                    return props != null && stream(props.spliterator(), false)
                            .anyMatch(prop -> prop.has("title"));
                })
                        .flatMap(hasTitle -> hasTitle ? safeId(node) : empty())
                        .switchIfEmpty(fromCallable(() -> node.has("title"))
                                .flatMap(has -> has ? safeId(node) : empty())
                        ).switchIfEmpty(safeId(node).map(id -> INVALID.getName() + "-" + id))
                        .map(name -> {
                            var source = node.has("properties") && node.get("properties").has("title")
                                    ? "properties.title"
                                    : node.has("title")
                                    ? "title"
                                    : "id";
                            return new Title(name, source);
                        })
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
