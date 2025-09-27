package io.wliamp.notion;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

        var outDir = Path.of("backup");
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
            var url = "https://api.notion.com/v1/search";
            var body = cursor == null
                    ? "{\"page_size\":100,\"filter\":{\"property\":\"object\",\"value\":\"page\"}}"
                    : "{\"page_size\":100,\"start_cursor\":\"" + cursor + "\",\"filter\":{\"property\":\"object\",\"value\":\"page\"}}";

            var request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", "2022-06-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Search API failed: " + response.statusCode() + "\n" + response.body());
            }

            var root = mapper.readTree(response.body());
            for (JsonNode res : root.get("results")) {
                if (res.get("object").asText().equals("page")) {
                    ids.add(res.get("id").asText().replaceAll("-", ""));
                }
            }

            cursor = root.has("next_cursor") && !root.get("next_cursor").isNull()
                    ? root.get("next_cursor").asText()
                    : null;

        } while (cursor != null);

        return ids;
    }

    /** Backup 1 page + toàn bộ block của nó */
    private void backupPage(String pageId, Path outDir) throws Exception {
        // Fetch page metadata
        var pageUrl = "https://api.notion.com/v1/pages/" + pageId;
        var pageRequest = HttpRequest.newBuilder()
                .uri(new URI(pageUrl))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2022-06-28")
                .GET()
                .build();

        var pageResponse = client.send(pageRequest, HttpResponse.BodyHandlers.ofString());
        if (pageResponse.statusCode() != 200) {
            System.err.println("⚠️ Failed to fetch page " + pageId);
            return;
        }

        JsonNode pageJson = mapper.readTree(pageResponse.body());

        // Lấy title từ properties (nếu có)
        String title = extractTitle(pageJson);
        if (title == null || title.isBlank()) {
            title = pageId;
        }

        // Tạo thư mục cho page
        Path pageDir = outDir.resolve(safeName(title));
        Files.createDirectories(pageDir);

        Files.writeString(pageDir.resolve("page.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(pageJson));

        // Fetch & backup blocks
        fetchAndBackupBlocks(pageId, pageDir);

        System.out.println("📦 Backed up page: " + title);
    }

    /** Đệ quy backup tất cả blocks của parent */
    private void fetchAndBackupBlocks(String parentId, Path outDir) throws Exception {
        String cursor = null;

        do {
            var url = "https://api.notion.com/v1/blocks/" + parentId + "/children?page_size=100"
                    + (cursor != null ? "&start_cursor=" + cursor : "");

            var request = HttpRequest.newBuilder()
                    .uri(new URI(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Notion-Version", "2022-06-28")
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("⚠️ Failed to fetch children for " + parentId + ": " + response.statusCode());
                return;
            }

            var root = mapper.readTree(response.body());
            for (JsonNode block : root.get("results")) {
                String blockId = block.get("id").asText().replaceAll("-", "");
                String type = block.get("type").asText();

                // Nếu là child_page → tạo thư mục con riêng
                if ("child_page".equals(type)) {
                    String childTitle = block.get("child_page").get("title").asText();
                    Path childDir = outDir.resolve(safeName(childTitle));
                    Files.createDirectories(childDir);

                    Files.writeString(childDir.resolve("page.json"),
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block));

                    // Đệ quy vào child_page
                    fetchAndBackupBlocks(blockId, childDir);

                } else if (block.has("has_children") && block.get("has_children").asBoolean()) {
                    // Block có children (toggle, column list, …)
                    Path blockDir = outDir.resolve("block-" + blockId);
                    Files.createDirectories(blockDir);

                    Files.writeString(blockDir.resolve("block.json"),
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block));

                    Path childrenDir = blockDir.resolve("children");
                    Files.createDirectories(childrenDir);
                    fetchAndBackupBlocks(blockId, childrenDir);

                } else {
                    // Block bình thường → lưu file json riêng
                    Path file = outDir.resolve("block-" + blockId + ".json");
                    Files.writeString(file,
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block));
                }
            }

            cursor = root.has("next_cursor") && !root.get("next_cursor").isNull()
                    ? root.get("next_cursor").asText()
                    : null;

        } while (cursor != null);
    }

    /** Lấy title từ properties["title"] */
    private String extractTitle(JsonNode pageJson) {
        if (pageJson.has("properties")) {
            for (JsonNode prop : pageJson.get("properties")) {
                if (prop.has("title")) {
                    var arr = prop.get("title");
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