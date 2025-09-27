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

    private void backupPage(String pageId, Path outDir) throws Exception {
        var pageDir = outDir.resolve(pageId);
        Files.createDirectories(pageDir);

        var pageUrl = "https://api.notion.com/v1/pages/" + pageId;
        var pageRequest = HttpRequest.newBuilder()
                .uri(new URI(pageUrl))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2022-06-28")
                .GET()
                .build();

        var pageResponse = client.send(pageRequest, HttpResponse.BodyHandlers.ofString());
        if (pageResponse.statusCode() == 200) {
            Files.writeString(pageDir.resolve("page.json"), pageResponse.body());
        }

        var blocksDir = pageDir.resolve("blocks");
        Files.createDirectories(blocksDir);

        fetchAndBackupBlocks(pageId, blocksDir);

        System.out.println("📦 Backed up page with recursive blocks: " + pageId);
    }

    private void fetchAndBackupBlocks(String parentId, Path outDir) throws Exception {
        String cursor = null;
        int blockIndex = 0;

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
                blockIndex++;

                if (block.has("has_children") && block.get("has_children").asBoolean()) {
                    Path blockDir = outDir.resolve("block-" + blockIndex + "-" + blockId);
                    Files.createDirectories(blockDir);

                    Files.writeString(blockDir.resolve("block.json"),
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block));

                    Path childrenDir = blockDir.resolve("children");
                    Files.createDirectories(childrenDir);
                    fetchAndBackupBlocks(blockId, childrenDir);

                } else {
                    var fileName = "block-" + blockIndex + "-" + blockId + ".json";
                    Files.writeString(outDir.resolve(fileName),
                            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(block));
                }
            }

            cursor = root.has("next_cursor") && !root.get("next_cursor").isNull()
                    ? root.get("next_cursor").asText()
                    : null;

        } while (cursor != null);
    }
}