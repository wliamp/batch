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
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@SpringBootApplication
public class Main implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebClient webClient;

    public Main() {
        String token = Optional.ofNullable(System.getenv("NOTION_TOKEN"))
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Mono<List<String>> pipeline = searchAllObjects()
                .flatMapSequential(obj -> backupObject(obj, outDir), 4)
                .map(obj -> obj.get("id").asText().replace("-", ""))
                .collectList()
                .flatMap(activeIds -> cleanupDeletedObjects(outDir, new HashSet<>(activeIds))
                        .thenReturn(activeIds));

        var activeIds = pipeline
                .doOnNext(ids -> log.info("✅ Backup completed. {} objects.", ids.size()))
                .doOnError(err -> log.error("❌ Backup pipeline failed", err))
                .block();

        log.info("🔚 Finished backup, exiting. Objects: {}", activeIds != null ? activeIds.size() : 0);
        System.exit(0);
    }

    /**
     * --- Cleanup ---
     */
    private Mono<Void> cleanupDeletedObjects(Path outDir, Set<String> activeIds) {
        return Mono.fromCallable(() -> Files.list(outDir))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromStream)
                .filter(Files::isDirectory)
                .flatMap(path -> {
                    Path pageJson = path.resolve("page.json");
                    if (!Files.exists(pageJson)) return Mono.empty();

                    return Mono.fromCallable(() -> mapper.readTree(Files.readString(pageJson)))
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(root -> root.get("id").asText().replace("-", ""))
                            .flatMap(id -> {
                                if (!activeIds.contains(id)) {
                                    log.info("🗑 Removing deleted page: {} (id={})", path, id);
                                    return deleteRecursively(path);
                                }
                                return Mono.empty();
                            })
                            .onErrorResume(ex -> {
                                log.warn("⚠ Could not parse {}: {}", pageJson, ex.toString());
                                return Mono.empty();
                            });
                }, 4)
                .then();
    }

    private Mono<Void> deleteRecursively(Path path) {
        return Mono.fromCallable(() -> {
                    try (Stream<Path> walker = Files.walk(path)) {
                        walker.sorted(Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException e) {
                                        throw new RuntimeException("Failed to delete: " + p, e);
                                    }
                                });
                    }
                    return (Void) null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(e -> log.warn("⚠ Could not delete {}", path, e))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * --- Search API ---
     */
    private Flux<JsonNode> searchAllObjects() {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode sort = mapper.createObjectNode();
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
                    JsonNode results = root.get("results");
                    return (results != null && results.isArray()) ? Flux.fromIterable(results) : Flux.empty();
                });
    }

    /**
     * --- Backup ---
     */
    private Mono<JsonNode> backupObject(JsonNode obj, Path outDir) {
        String id = obj.get("id").asText();
        String shortId = id.replace("-", "");
        TitleResult titleResult = extractTitle(obj, shortId);

        Path objDir = outDir.resolve(safeName(titleResult.title));

        Mono<Void> mkdir = Mono.fromRunnable(() -> {
            try {
                Files.createDirectories(objDir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();

        Mono<List<JsonNode>> blocksMono = fetchBlockTree(shortId).collectList();

        Mono<Void> writeFiles = blocksMono.flatMap(blocks -> Mono.fromRunnable(() -> {
            try {
                Files.writeString(objDir.resolve("page.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
                Files.writeString(objDir.resolve("blocks.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocks));

                ObjectNode meta = mapper.createObjectNode();
                meta.put("id", id);
                meta.put("shortId", shortId);
                meta.put("title", titleResult.title);
                meta.put("backup_time", Instant.now().toString());
                meta.put("title_source", titleResult.source);

                Files.writeString(objDir.resolve("meta.json"),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).subscribeOn(Schedulers.boundedElastic())).then();

        return mkdir.then(writeFiles).thenReturn(obj);
    }

    /**
     * --- Block Tree ---
     */
    private Flux<JsonNode> fetchBlockTree(String parentId) {
        return fetchBlocksPage(parentId);
    }

    private Flux<JsonNode> fetchBlocksPage(String parentId) {
        String uri = "/blocks/" + parentId + "/children?page_size=100";

        return webClient.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
                .flatMapMany(root -> {
                    JsonNode results = root.get("results");
                    return (results != null && results.isArray())
                            ? Flux.fromIterable(results).flatMapSequential(this::enrichBlock, 2)
                            : Flux.empty();
                });
    }

    private Mono<JsonNode> enrichBlock(JsonNode block) {
        if (block.path("has_children").asBoolean(false)) {
            String childId = block.get("id").asText().replace("-", "");
            return fetchBlockTree(childId)
                    .collectList()
                    .map(children -> {
                        ObjectNode enriched = mapper.createObjectNode();
                        enriched.setAll((ObjectNode) block);
                        enriched.set("children", mapper.valueToTree(children));
                        return enriched;
                    });
        }
        return Mono.just(block);
    }

    /**
     * --- Utils ---
     */
    private record TitleResult(String title, String source) {
    }

    private TitleResult extractTitle(JsonNode obj, String shortId) {
        // 1. Try properties.title
        Optional<String> fromProps = Optional.ofNullable(obj.get("properties"))
                .flatMap(node -> StreamSupport.stream(node.spliterator(), false)
                        .filter(prop -> prop.has("title"))
                        .map(prop -> prop.get("title"))
                        .filter(JsonNode::isArray)
                        .filter(arr -> !arr.isEmpty())
                        .map(arr -> arr.get(0).path("plain_text").asText(null))
                        .filter(Objects::nonNull)
                        .findFirst());

        if (fromProps.isPresent()) {
            return new TitleResult(fromProps.get(), "properties.title");
        }

        // 2. Try obj.title
        Optional<String> fromTitle = Optional.ofNullable(obj.get("title"))
                .filter(JsonNode::isArray)
                .filter(arr -> !arr.isEmpty())
                .map(arr -> arr.get(0).path("plain_text").asText(null));

        return fromTitle.map(s -> new TitleResult(s, "title")).orElseGet(() -> new TitleResult("untitled-" + shortId, "fallback"));

        // 3. Fallback
    }

    private String safeName(String input) {
        if (input == null) return "untitled";
        return input.replaceAll("[^a-zA-Z0-9-_.]", "_");
    }
}