package io.wliamp.notion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Service
@Slf4j
@RequiredArgsConstructor
public class SearchService {
    private final WebClient webClient;
    private final ObjectMapper mapper;

    public Flux<JsonNode> search(String token) {
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
                .flatMapMany(root -> {
                    var results = root.get("results");
                    return results == null || !results.isArray()
                            ? Flux.empty()
                            : Flux.fromIterable(results);
                });
    }
}

