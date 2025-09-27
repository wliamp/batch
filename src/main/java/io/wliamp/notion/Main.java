package io.wliamp.notion;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@SpringBootApplication
public class Main implements CommandLineRunner {

    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        var token = System.getenv("NOTION_TOKEN");
        var pageId = System.getenv("NOTION_PAGE_ID");
        var repoPath = ".";

        if (token == null || pageId == null) {
            throw new IllegalArgumentException("Missing NOTION_TOKEN or NOTION_PAGE_ID in ENV");
        }

        var client = HttpClient.newHttpClient();
        var url = "https://api.notion.com/v1/blocks/" + pageId + "/children?page_size=100";
        var request = HttpRequest.newBuilder()
                .uri(new URI(url))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", "2022-06-28")
                .header("Content-Type", "application/json")
                .GET()
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("API call failed: " + response.statusCode() + "\n" + response.body());
        }

        var root = mapper.readTree(response.body());
        var sb = new StringBuilder();

        for (JsonNode block : root.get("results")) {
            var type = block.get("type").asText();
            switch (type) {
                case "heading_1" -> sb.append("# ")
                        .append(getText(block.get("heading_1")))
                        .append("\n\n");
                case "heading_2" -> sb.append("## ")
                        .append(getText(block.get("heading_2")))
                        .append("\n\n");
                case "heading_3" -> sb.append("### ")
                        .append(getText(block.get("heading_3")))
                        .append("\n\n");
                case "paragraph" -> sb.append(getText(block.get("paragraph")))
                        .append("\n\n");
                case "bulleted_list_item" -> sb.append("- ")
                        .append(getText(block.get("bulleted_list_item")))
                        .append("\n");
                case "numbered_list_item" -> sb.append("1. ")
                        .append(getText(block.get("numbered_list_item")))
                        .append("\n");
                default -> sb.append("`[").append(type).append("]` (unsupported)\n");
            }
        }

        var outFile = Paths.get(repoPath, "page.md");
        Files.writeString(outFile, sb.toString());
        System.out.println("Exported Notion page to Markdown: " + outFile.toAbsolutePath());
    }

    private String getText(JsonNode node) {
        if (node == null || !node.has("rich_text")) return "";
        var texts = node.get("rich_text");
        var sb = new StringBuilder();
        for (JsonNode t : texts) {
            sb.append(t.get("plain_text").asText());
        }
        return sb.toString();
    }
}
