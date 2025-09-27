package io.wliamp.notion;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@SpringBootApplication
public class Main implements CommandLineRunner {

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

        var outFile = Path.of(repoPath, "page.json");
        Files.writeString(outFile, response.body());

        System.out.println("Exported Notion page raw JSON: " + outFile.toAbsolutePath());
    }
}