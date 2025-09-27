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
import java.util.ArrayList;
import java.util.List;

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
        token = System.getenv("NOTION_TOKEN");
        if (token == null) {
            throw new IllegalArgumentException("Missing NOTION_TOKEN in ENV");
        }

        Path outDir = Path.of("backup");
        Files.createDirectories(outDir);

        List<String> pageIds = searchAllPages();

        for (String pageId : pageIds) {
            backupPage(pageId, outDir);
        }

        System.out.println("✅ Backup completed. Total pages: " + pageIds.size());
    }

    /** Tìm tất cả page mà integration có quyền */
    private List<String> searchAllPages() throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;

        do {
            String url = "https://api.notion.com/v1/search";
            String body = cursor == null
                    ? "{\"page_size\":100,\"filter\":{\"property\":\"object\",\"value\":\"page\"}}"
                    : "{\"page_size\":100,\"start_cursor\":\"" + cursor + "\",\"filter\":{\"property\":\"object\",\"value\":\"page\"}}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", "2022-06-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Search API failed: " + response.statusCode() + "\n" + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            for (JsonNode res : root.get("results")) {
                if ("page".equals(res.get("object").asText())) {
                    ids.add(res.get("id").asText().replaceAll("-", ""));
                }
            }

            cursor = root.has("next_cursor") && !root.get("next_cursor").isNull()
                    ? root.get("next_cursor").asText()
                    : null;

        } while (cursor != null);

        return ids;
    }

    /** Backup 1 page (metadata + toàn bộ block tree) */
    private void backupPage(String pageId, Path outDir) throws Exception {
        // Metadata page
        String pageUrl = "https://api.notion.com/v1/pages/" + pageId;
        HttpRequest pageRequest = HttpRequest.newBuilder()
                .uri(new URI(pageUrl))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2022-06-28")
                .GET()
                .build();

        HttpResponse<String> pageResponse = client.send(pageRequest, HttpResponse.BodyHandlers.ofString());
        if (pageResponse.statusCode() != 200) {
            System.err.println("⚠️ Failed to fetch page " + pageId);
            return;
        }

        JsonNode pageJson = mapper.readTree(pageResponse.body());

        // Lấy title từ properties
        String title = extractTitle(pageJson);
        if (title == null || title.isBlank()) {
            title = pageId;
        }

        // Tạo thư mục cho page
        Path pageDir = outDir.resolve(safeName(title));
        Files.createDirectories(pageDir);

        // Lưu page.json
        Files.writeString(pageDir.resolve("page.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pageJson));

        // Lấy cây blocks
        List<JsonNode> blocksTree = fetchBlocksTree(pageId);

        // Lưu blocks.json
        Files.writeString(pageDir.resolve("blocks.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(blocksTree));

        System.out.println("📦 Backed up page: " + title);
    }

    /** Đệ quy lấy toàn bộ block tree */
    private List<JsonNode> fetchBlocksTree(String parentId) throws Exception {
        List<JsonNode> allBlocks = new ArrayList<>();
        String cursor = null;

        do {
            String url = "https://api.notion.com/v1/blocks/" + parentId + "/children?page_size=100"
                    + (cursor != null ? "&start_cursor=" + cursor : "");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", "2022-06-28")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("⚠️ Failed to fetch children for " + parentId + ": " + response.statusCode());
                return allBlocks;
            }

            JsonNode root = mapper.readTree(response.body());
            for (JsonNode block : root.get("results")) {
                if (block.has("has_children") && block.get("has_children").asBoolean()) {
                    ObjectNode blockObj = mapper.createObjectNode();
                    blockObj.setAll((ObjectNode) block);

                    List<JsonNode> children = fetchBlocksTree(block.get("id").asText().replaceAll("-", ""));
                    blockObj.set("children", mapper.valueToTree(children));

                    allBlocks.add(blockObj);
                } else {
                    allBlocks.add(block);
                }
            }

            cursor = root.has("next_cursor") && !root.get("next_cursor").isNull()
                    ? root.get("next_cursor").asText()
                    : null;

        } while (cursor != null);

        return allBlocks;
    }

    /** Lấy title từ properties["title"] */
    private String extractTitle(JsonNode pageJson) {
        if (pageJson.has("properties")) {
            for (JsonNode prop : pageJson.get("properties")) {
                if (prop.has("title")) {
                    JsonNode arr = prop.get("title");
                    if (arr.isArray() && arr.size() > 0) {
                        return arr.get(0).get("plain_text").asText();
                    }
                }
            }
        }
        return null;
    }

    /** Đổi tên hợp lệ cho folder/file */
    private String safeName(String input) {
        return input.replaceAll("[^a-zA-Z0-9-_]", "_");
    }
}
