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
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.StreamSupport;

@SuppressWarnings("ALL")
@SpringBootApplication
class Main implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    Main() {
        var token = Optional.ofNullable(System.getenv("NOTION_TOKEN"))
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("Missing NOTION_TOKEN in ENV"));

        this.webClient = WebClient.builder()
                .baseUrl("https://api.notion.com/v1")
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Notion-Version", "2022-06-28")
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) {
        Path outDir;
        try {
            outDir = Files.createDirectories(Path.of("backup"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            var list = searchAllObjects()
                    .flatMapSequential(obj -> backupObject(obj, outDir), 4)
                    .doOnNext(obj -> log.info("📦 Backed up: {}", extractTitle(obj).orElse(obj.get("id").asText())))
                    .collectList()
                    .doOnNext(res -> log.info("✅ Backup completed. {} objects.", res.size()))
                    .doOnError(err -> log.error("❌ Backup failed", err))
                    .block();

            log.info("🔚 Finished backup, exiting. Objects: {}", list != null ? list.size() : 0);
        } finally {
            System.exit(0);
        }
    }

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
                    return (results != null && results.isArray()) ? Flux.fromIterable(results) : Flux.empty();
                });
    }

    private Mono<JsonNode> backupObject(JsonNode obj, Path outDir) {
        var id = obj.get("id").asText().replace("-", "");
        var title = extractTitle(obj).orElse(id);
        var objDir = outDir.resolve(safeName(title));

        return Mono.fromRunnable(() -> {
                    try {
                        Files.createDirectories(objDir);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(fetchBlockTree(id)
                        .collectList()
                        .flatMap(blocks -> Mono.fromRunnable(() -> {
                            try {
                                Files.writeString(objDir.resolve("page.json"),
                                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
                                Files.writeString(objDir.resolve("blocks.json"),
                                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }).subscribeOn(Schedulers.boundedElastic()))
                ).thenReturn(obj);
    }

    private Flux<JsonNode> fetchBlockTree(String parentId) {
        return fetchBlocksPage(parentId);
    }

    private Flux<JsonNode> fetchBlocksPage(String parentId) {
        var uri = "/blocks/" + parentId + "/children?page_size=100";

        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
                .flatMapMany(root -> {
                    var results = root.get("results");

                    return (results != null && results.isArray())
                            ? Flux.fromIterable(results)
                            .flatMapSequential(this::enrichBlock, 2)
                            : Flux.empty();
                });
    }

    private Mono<JsonNode> enrichBlock(JsonNode block) {
        if (block.path("has_children").asBoolean(false)) {
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
        return Mono.just(block);
    }

    private Optional<String> extractTitle(JsonNode obj) {
        return Optional.ofNullable(obj.get("properties"))
                .flatMap(node -> StreamSupport.stream(node.spliterator(), false)
                        .filter(prop -> prop.has("title"))
                        .map(prop -> prop.get("title"))
                        .filter(JsonNode::isArray)
                        .filter(arr -> !arr.isEmpty())
                        .map(arr -> arr.get(0).path("plain_text").asText(null))
                        .filter(Objects::nonNull)
                        .findFirst())
                .or(() -> Optional.ofNullable(obj.get("title"))
                        .filter(JsonNode::isArray)
                        .filter(arr -> !arr.isEmpty())
                        .map(arr -> arr.get(0).path("plain_text").asText(null)));
    }

    private String safeName(String input) {
        return input.replaceAll("[^a-zA-Z0-9-_.]", "_");
    }
}
