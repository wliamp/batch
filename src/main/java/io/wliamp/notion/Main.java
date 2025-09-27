package io.wliamp.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.StreamSupport;

@SpringBootApplication
public class Main implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    public Main() {
        var token = Optional.ofNullable(System.getenv("NOTION_TOKEN"))
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing NOTION_TOKEN in ENV"));

        this.webClient = WebClient.builder()
                .baseUrl("https://api.notion.com/v1")
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Notion-Version", "2022-06-28")
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)) // 16MB buffer
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        var outDir = Files.createDirectories(Path.of("backup"));

        searchAllObjects()
                .flatMap(obj -> backupObject(obj, outDir), 8) // max concurrency = 8
                .collectList()
                .doOnNext(list -> log.info("✅ Backup completed. {} objects.%n", list.size()))
                .block();
    }

    // === Search all pages & databases ===
    private Flux<JsonNode> searchAllObjects() {
        var body = mapper.createObjectNode();
        var sort = mapper.createObjectNode();
        sort.put("direction", "descending");
        sort.put("timestamp", "last_edited_time");
        body.set("sort", sort);
        body.put("page_size", 100);

        return webClient.post()
                .uri("/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMapMany(root -> {
                    var results = root.get("results");
                    if (results == null || !results.isArray()) return Flux.empty();
                    return Flux.fromIterable(results);
                });
    }

    // === Backup one object ===
    private Mono<JsonNode> backupObject(JsonNode obj, Path outDir) {
        var id = obj.get("id").asText().replace("-", "");
        var title = extractTitle(obj).orElse(id);
        var safeTitle = safeName(title);
        var objDir = outDir.resolve(safeTitle);

        Mono<Void> ensureDir = Mono.fromCallable(() -> Files.createDirectories(objDir))
                .subscribeOn(Schedulers.boundedElastic())
                .then();

        Mono<Void> writePage = ensureDir.then(
                Mono.fromCallable(() -> {
                    Files.writeString(
                            objDir.resolve("page.json"),
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
                    return (Void) null;
                }).subscribeOn(Schedulers.boundedElastic())
        );

        Mono<Void> writeBlocks = fetchBlockTree(id)
                .collectList()
                .flatMap(blocks ->
                        Mono.fromCallable(() -> {
                            Files.writeString(
                                    objDir.resolve("blocks.json"),
                                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks));
                            return (Void) null;
                        }).subscribeOn(Schedulers.boundedElastic())
                );

        return Mono.when(writePage, writeBlocks)
                .thenReturn(obj)
                .doOnSuccess(_ -> log.info("\uD83D\uDCE6 Backed up: {}", title));
    }

    // === Fetch block tree recursively ===
    private Flux<JsonNode> fetchBlockTree(String parentId) {
        return fetchBlocksPage(parentId, null);
    }

    private Flux<JsonNode> fetchBlocksPage(String parentId, String cursor) {
        var uri = "/blocks/" + parentId + "/children?page_size=100"
                + (cursor != null ? "&start_cursor=" + cursor : "");

        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
                .flatMapMany(root -> {
                    var results = root.get("results");
                    Flux<JsonNode> blocks = (results == null || !results.isArray())
                            ? Flux.empty()
                            : Flux.fromIterable(results)
                            .flatMap(this::enrichBlock, 6);

                    var nextCursor = root.path("next_cursor").asText(null);
                    return nextCursor != null && !nextCursor.isBlank()
                            ? Flux.concat(blocks, fetchBlocksPage(parentId, nextCursor))
                            : blocks;
                });
    }

    // enrich: nếu block có children → attach children
    private Mono<JsonNode> enrichBlock(JsonNode block) {
        if (!block.path("has_children").asBoolean(false)) return Mono.just(block);

        var childId = block.get("id").asText().replace("-", "");
        return fetchBlockTree(childId)
                .collectList()
                .map(children -> {
                    var enriched = mapper.createObjectNode();
                    enriched.setAll((ObjectNode) block);
                    enriched.set("children", mapper.valueToTree(children));
                    return enriched;
                });
    }

    private Optional<String> extractTitle(JsonNode obj) {
        // Page: lấy từ properties.title
        var fromProperties = Optional.ofNullable(obj.get("properties"))
                .stream()
                .flatMap(node -> StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(node.elements(), Spliterator.ORDERED), false))
                .filter(prop -> prop.has("title"))
                .map(prop -> prop.get("title"))
                .filter(JsonNode::isArray)
                .filter(arr -> !arr.isEmpty())
                .map(arr -> arr.get(0).path("plain_text").asText(null))
                .filter(Objects::nonNull)
                .findFirst();

        if (fromProperties.isPresent()) return fromProperties;

        // Database: lấy từ "title"
        return Optional.ofNullable(obj.get("title"))
                .filter(JsonNode::isArray)
                .filter(arr -> !arr.isEmpty())
                .map(arr -> arr.get(0).path("plain_text").asText(null));
    }

    private String safeName(String input) {
        return input.replaceAll("[^a-zA-Z0-9-_.]", "_");
    }
}
