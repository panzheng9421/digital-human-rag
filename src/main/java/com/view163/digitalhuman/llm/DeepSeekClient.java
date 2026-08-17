package com.view163.digitalhuman.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 生成客户端：调用 DeepSeek chat（OpenAI 兼容模式） */
@Component
public class DeepSeekClient {

    private final String apiKey;
    private final double temperature;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String URL = "https://api.deepseek.com/v1/chat/completions";

    public DeepSeekClient(@Value("${app.deepseek-api-key:}") String apiKey,
                          @Value("${app.temperature:0.7}") double temperature) {
        this.apiKey = apiKey;
        this.temperature = temperature;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public String chat(String system, String user) {
        try {
            Map<String, Object> msgSys = new LinkedHashMap<>();
            msgSys.put("role", "system");
            msgSys.put("content", system);

            Map<String, Object> msgUser = new LinkedHashMap<>();
            msgUser.put("role", "user");
            msgUser.put("content", user);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "deepseek-chat");
            body.put("temperature", temperature);
            body.put("messages", List.of(msgSys, msgUser));

            String json = mapper.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("DeepSeek API error " + resp.statusCode() + ": " + resp.body());
            }

            ChatResponse cr = mapper.readValue(resp.body(), ChatResponse.class);
            return cr.choices.get(0).message.content;
        } catch (Exception e) {
            throw new RuntimeException("DeepSeek chat failed", e);
        }
    }
}
