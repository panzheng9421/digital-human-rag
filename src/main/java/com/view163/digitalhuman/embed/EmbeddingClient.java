package com.view163.digitalhuman.embed;

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

/**
 * 向量化客户端：调用阿里云百炼 text-embedding-v3（OpenAI 兼容模式）。
 * 注意：DeepSeek 不提供 embedding，必须单独接一个向量模型。
 */
@Component
public class EmbeddingClient {

    private final String apiKey;
    private final int dimension;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";

    public EmbeddingClient(@Value("${app.dashscope-api-key:}") String apiKey,
                           @Value("${app.dimension:1024}") int dimension) {
        this.apiKey = apiKey;
        this.dimension = dimension;
    }

    public float[] embed(String text) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", "text-embedding-v3");
            body.put("input", text);
            body.put("dimensions", dimension);
            String json = mapper.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Embedding API error " + resp.statusCode() + ": " + resp.body());
            }

            EmbeddingResponse er = mapper.readValue(resp.body(), EmbeddingResponse.class);
            if (er.data == null || er.data.isEmpty()) {
                throw new RuntimeException("Empty embedding response");
            }
            return toFloatArray(er.data.get(0).embedding);
        } catch (Exception e) {
            throw new RuntimeException("Embedding failed", e);
        }
    }

    private float[] toFloatArray(List<Double> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            a[i] = list.get(i).floatValue();
        }
        return a;
    }
}
