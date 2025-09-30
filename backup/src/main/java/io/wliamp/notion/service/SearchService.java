package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import static reactor.core.publisher.Flux.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class SearchService {
    private final WebClient webClient;
    private final ObjectMapper mapper;

    public Flux<JsonNode> search(String token) {
        log.info("🔍 Searching Notion workspace");

        var body = mapper.createObjectNode();
        body.set("sort", mapper.createObjectNode()
                .put("direction", "descending")
                .put("timestamp", "last_edited_time"));
        body.put("page_size", 100);

        return webClient.post()
                .uri("/search")
                .header("Authorization", "Bearer " + token)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .doOnNext(root -> log.info("📦 Raw search response: has 'results'={}", root.has("results")))
                .flatMapMany(root -> {
                    var results = root.get("results");

                    return results != null && results.isArray()
                            ? fromIterable(results)
                            .doOnSubscribe(s -> log.info("🔍 Processing results array"))
                            .doOnNext(r -> log.trace("➡️ Result item: {}", r))
                            .doOnComplete(() -> log.info("✅ Search returned {} objects", results.size()))
                            : Flux.<JsonNode>empty()
                            .doOnSubscribe(s -> log.warn("⚠️ No results array in search response"));
                })
                .doOnError(e -> log.error("❌ Search request failed", e));
    }
}

