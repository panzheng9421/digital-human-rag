package com.view163.digitalhuman.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.view163.digitalhuman.config.AppProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 文生图接口（工作台模块1）：POST /api/image/generate {prompt, size, ratio}
 *   ratio 支持 16:9（横版，默认）/ 9:16（竖版），与 size(2K/4K) 组合换算为方舟自定义像素尺寸。
 *
 * 走火山方舟 Seedream 图片生成（与视频引擎共用同一把 ARK key）：
 *   POST https://ark.cn-beijing.volces.com/api/v3/images/generations
 *   body: {model, prompt, size, response_format:"url", watermark:false}
 *   响应: {data:[{url, size, output_format}], created, model}
 *
 * 同步返回（一般 5-30 秒），返回 {url, size}；url 24 小时有效，页面直接预览/下载。
 * 默认模型 doubao-seedream-4-0-250828，可在 application.yml 用 app.image.model-id 覆盖。
 */
@RestController
@CrossOrigin(origins = "*") // 与 WizardController 一致：GitHub Pages 跨域调用；挂 ECS 后收紧
public class ImageController {

    private final AppProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** 方舟文生图模型，可用配置覆盖（如换 doubao-seedream-5-0-lite） */
    @Value("${app.image.model-id:doubao-seedream-4-0-250828}")
    private String modelId;

    public ImageController(AppProperties props) {
        this.props = props;
    }

    @PostMapping("/api/image/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> body) {
        String prompt = body.get("prompt") == null ? "" : body.get("prompt").toString().trim();
        if (prompt.isEmpty()) return Map.of("error", "prompt 不能为空");
        String size = body.get("size") == null || body.get("size").toString().trim().isEmpty()
                ? "2K" : body.get("size").toString().trim();
        String ratio = body.get("ratio") == null || body.get("ratio").toString().trim().isEmpty()
                ? "16:9" : body.get("ratio").toString().trim();

        AppProperties.Video.Volcengine volc = props.getVideo().getVolcengine();
        String apiKey = (volc.getApiKey() != null && !volc.getApiKey().isEmpty())
                ? volc.getApiKey() : System.getenv("ARK_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            return Map.of("error", "缺少方舟 key：请在 application.yml 配 volcengine.api-key 或设环境变量 ARK_API_KEY");
        }

        try {
            // 画幅+清晰度 → 方舟自定义像素尺寸（Seedream 支持宽x高，单边上限 4096）
            //   16:9：2K=2560x1440，4K=3840x2160；9:16：2K=1440x2560，4K=2160x3840
            String pixelSize = switch (ratio + ":" + size) {
                case "16:9:2K" -> "2560x1440";
                case "16:9:4K" -> "3840x2160";
                case "9:16:2K" -> "1440x2560";
                case "9:16:4K" -> "2160x3840";
                default -> size; // 其他清晰度档位原样透传
            };

            ObjectNode root = mapper.createObjectNode();
            root.put("model", modelId);
            root.put("prompt", prompt);
            root.put("size", pixelSize);
            root.put("response_format", "url");
            root.put("watermark", false);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://ark.cn-beijing.volces.com/api/v3/images/generations"))
                    .timeout(Duration.ofSeconds(180))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of("error", "方舟图片接口 HTTP " + resp.statusCode() + "：" + resp.body());
            }
            JsonNode data = mapper.readTree(resp.body()).path("data");
            if (!data.isArray() || data.isEmpty()) {
                return Map.of("error", "方舟返回无图片数据：" + resp.body());
            }
            JsonNode first = data.get(0);
            String url = first.path("url").asText("");
            if (url.isEmpty()) return Map.of("error", "方舟返回缺 url 字段：" + resp.body());
            return Map.of("url", url, "size", first.path("size").asText(size), "model", modelId);
        } catch (Exception e) {
            return Map.of("error", "文生图失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }
}
