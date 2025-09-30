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

    public Flux<JsonNode> fetchBlockTree(String parentId, String token) {
        return webClient.get()
                .uri("/blocks/{id}/children?page_size=100", parentId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMapMany(root -> {
                    var results = root.get("results");
                    return results == null || !results.isArray()
                            ? Flux.empty()
                            : fromIterable(results);
                });
    }
}

