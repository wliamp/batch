package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import static reactor.core.publisher.Flux.fromIterable;

@Service
@Slf4j
@RequiredArgsConstructor
public class FetchService {
    private final WebClient webClient;

    public Flux<JsonNode> fetch(String parentId, String token) {
        log.info("📥 Fetching children blocks for parentId={}", parentId);

        return webClient.get()
                .uri("/blocks/{id}/children?page_size=100", parentId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnNext(root -> log.info("📦 Raw fetch response for [{}]: has 'results'={}",
                        parentId, root.has("results")))
                .flatMapMany(root -> {
                    var results = root.get("results");

                    return results != null && results.isArray()
                            ? fromIterable(results)
                            .doOnSubscribe(s -> log.info("📥 Processing children for [{}]", parentId))
                            .doOnComplete(() -> log.info("✅ Fetched {} blocks for parentId={}", results.size(), parentId))
                            : Flux.<JsonNode>empty()
                            .doOnSubscribe(s -> log.warn("⚠️ No results array in fetch response for [{}]", parentId));
                })
                .doOnError(e -> log.error("❌ Failed to fetch children for [{}]", parentId, e));
    }
}

