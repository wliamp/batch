package io.wliamp.notion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@SpringBootApplication
public class Main implements CommandLineRunner {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private String token;

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        token = envOrThrow("NOTION_TOKEN", "Missing NOTION_TOKEN in ENV");

        List<String> rootPageIds = Arrays.stream(
                        envOrThrow("NOTION_ROOT_PAGES", "Missing NOTION_ROOT_PAGES in ENV (comma-separated page IDs)")
                                .split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        Path outDir = Path.of("backup");
        Files.createDirectories(outDir);

        rootPageIds.forEach(id -> {
            try {
                backupPage(id, outDir);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        System.out.println("✅ Backup completed. Total root pages: " + rootPageIds.size());
    }

    /**
     * Backup 1 page (metadata + block tree)
     */
    private void backupPage(String pageId, Path outDir) throws Exception {
        JsonNode pageJson = fetchPage(pageId);

        String title = Optional.ofNullable(extractTitle(pageJson))
                .filter(t -> !t.isBlank())
                .orElse(pageId);

        Path pageDir = outDir.resolve(safeName(title));
        Files.createDirectories(pageDir);

        Files.writeString(pageDir.resolve("page.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pageJson));

        List<JsonNode> blocksTree = fetchBlocksTree(pageId);
        Files.writeString(pageDir.resolve("blocks.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocksTree));

        System.out.println("📦 Backed up page: " + title);
    }

    /**
     * Lấy metadata page
     */
    private JsonNode fetchPage(String pageId) throws Exception {
        HttpRequest request = requestBuilder("https://api.notion.com/v1/pages/" + pageId).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assert response.statusCode() == 200 : "⚠️ Failed to fetch page " + pageId + ": " + response.statusCode();
        return mapper.readTree(response.body());
    }

    /**
     * Lấy toàn bộ block tree (recursive)
     */
    private List<JsonNode> fetchBlocksTree(String parentId) throws Exception {
        return fetchBlocksPaged(parentId, null);
    }

    /**
     * Lấy từng trang con, ghép lại bằng Stream
     */
    private List<JsonNode> fetchBlocksPaged(String parentId, String cursor) throws Exception {
        String url = "https://api.notion.com/v1/blocks/" + parentId + "/children?page_size=100"
                + (cursor != null ? "&start_cursor=" + cursor : "");

        HttpRequest request = requestBuilder(url).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assert response.statusCode() == 200 : "⚠️ Failed to fetch children for " + parentId + ": " + response.statusCode();

        JsonNode root = mapper.readTree(response.body());

        List<JsonNode> currentPageBlocks = Optional.ofNullable(root.get("results"))
                .stream()
                .flatMap(node ->
                        StreamSupport.stream(
                                Spliterators.spliteratorUnknownSize(node.elements(), Spliterator.ORDERED),
                                false
                        )
                ).map(this::enrichBlock)
                .toList();

        return Optional.ofNullable(root.get("next_cursor"))
                .filter(n -> !n.isNull())
                .map(JsonNode::asText)
                .map(next -> Stream.concat(currentPageBlocks.stream(), unchecked(() -> fetchBlocksPaged(parentId, next)).stream())
                        .collect(Collectors.toList()))
                .orElse(currentPageBlocks);
    }

    /**
     * Xử lý block có children
     */
    private JsonNode enrichBlock(JsonNode block) {
        return Optional.ofNullable(block.get("has_children"))
                .map(JsonNode::asBoolean)
                .filter(Boolean::booleanValue)
                .map(x -> buildBlockWithChildren(block))
                .orElse(block);
    }

    private JsonNode buildBlockWithChildren(JsonNode block) {
        ObjectNode blockObj = mapper.createObjectNode();
        blockObj.setAll((ObjectNode) block);

        String childId = block.get("id").asText().replaceAll("-", "");
        List<JsonNode> children = unchecked(() -> fetchBlocksTree(childId));
        blockObj.set("children", mapper.valueToTree(children));

        return blockObj;
    }

    /**
     * Lấy title từ properties
     */
    private String extractTitle(JsonNode pageJson) {
        return Optional.ofNullable(pageJson.get("properties"))
                .stream()
                .flatMap(props -> StreamSupport.stream(Spliterators.spliteratorUnknownSize(props.elements(), 0), false))
                .map(p -> p.get("title"))
                .filter(Objects::nonNull)
                .filter(JsonNode::isArray)
                .flatMap(arr -> StreamSupport.stream(arr.spliterator(), false))
                .findFirst()
                .map(n -> n.get("plain_text").asText())
                .orElse(null);
    }

    /**
     * Helpers
     */
    private String envOrThrow(String key, String message) {
        return Optional.ofNullable(System.getenv(key))
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(message));
    }

    private HttpRequest.Builder requestBuilder(String url) throws Exception {
        return HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2022-06-28")
                .header("Content-Type", "application/json");
    }

    private String safeName(String input) {
        return input.replaceAll("[^a-zA-Z0-9-_]", "_");
    }

    /**
     * Wrapper để bỏ try/catch inline
     */
    private static <T> T unchecked(ThrowingSupplier<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
