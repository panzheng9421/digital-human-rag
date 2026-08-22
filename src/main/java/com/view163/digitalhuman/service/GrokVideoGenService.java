package com.view163.digitalhuman.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.view163.digitalhuman.config.AppProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage3 出片（Grok 引擎）：调用 api.x.ai 的 grok-imagine-video-1.5（文本/参考图生视频）。
 * 流程：POST /v1/videos/generations -> GET /v1/videos/{request_id} 轮询 -> 下载 MP4。
 * 文档：https://docs.x.ai/developers/model-capabilities/video/generation
 *
 * 关键约束（来自官方文档）：
 *   - duration 上限 15s（超过会被裁剪，这里统一 clamp 到 15）
 *   - resolution 形如 720p/1080p（与 Seedance 的 "720" 字符串不同，需补 "p"）
 *   - image 与 reference_images 互斥，二者同时传会返回 400，这里统一走 reference_images 保证一致性
 *   - 视频 URL 为临时链接，需及时下载
 */
public class GrokVideoGenService implements VideoGenerator {

    private final String apiKey;
    private final String modelId;
    private final List<String> referenceImageUrls;
    private final List<String> referenceAudioIds;
    private final int seconds;
    private final String resolution;
    private final String outputDir;
    private final int retryAttempts;
    private final String baseUrl;   // Grok API 基地址（直连 api.x.ai 或中转站 sub.xlcsh.icu 等），勿带末尾斜杠

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    // api.x.ai 使用标准证书链，无需 trust-all 客户端
    private final HttpClient http = HttpClient.newHttpClient();

    public GrokVideoGenService(AppProperties props, String persona) {
        AppProperties.Video v = props.getVideo();
        AppProperties.Video.Grok g = v.getGrok();
        String envKey = System.getenv("XAI_API_KEY");
        this.apiKey = (g.getApiKey() != null && !g.getApiKey().isEmpty())
                ? g.getApiKey() : (envKey != null ? envKey : "");
        this.modelId = "grok-imagine-video-1.5";   // Grok 视频模型固定
        this.referenceImageUrls = v.getReferenceImageUrlsFor(persona);
        this.referenceAudioIds = g.getReferenceAudioIds() == null ? List.of() : g.getReferenceAudioIds();
        this.seconds = Math.min(v.getSeconds(), 15);   // Grok 上限 15s
        this.resolution = toResolution(v.getSize());
        this.outputDir = v.getOutputDir();
        this.retryAttempts = Math.max(0, v.getRetryAttempts());
        String cfgBase = g.getBaseUrl();
        this.baseUrl = (cfgBase != null && !cfgBase.isEmpty()) ? cfgBase : "https://api.x.ai";
        System.out.println("[Grok INIT] baseUrl=" + this.baseUrl + " model=" + modelId
                + " seconds=" + this.seconds + " refImages=" + this.referenceImageUrls.size()
                + " refAudios=" + this.referenceAudioIds + " retries=" + this.retryAttempts);
    }

    private static String toResolution(String size) {
        if (size == null || size.isEmpty()) return "480p";
        if (size.endsWith("p")) return size;
        return size + "p";
    }

    @Override
    public String generate(String visualPrompt, int segIndex, int total) throws Exception {
        Path outDir = Paths.get(outputDir);
        Files.createDirectories(outDir);
        String baseName = segIndex > 0
                ? String.format("seg_%02d.mp4", segIndex)
                : ("output_" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".mp4");
        Path target = outDir.resolve(baseName);
        Path tmp = outDir.resolve(baseName + ".part");

        int attempts = retryAttempts + 1;
        Exception last = null;
        for (int a = 0; a < attempts; a++) {
            try {
                String requestId = submit(visualPrompt);
                System.out.println("[Grok] 片段 " + segIndex + "/" + total + " 已提交：" + requestId
                        + (attempts > 1 ? "（第 " + (a + 1) + "/" + attempts + " 次尝试）" : ""));
                String videoUrl = waitForCompletion(requestId);
                System.out.println("[Grok] 片段 " + segIndex + " 生成完成，下载中…");
                download(videoUrl, tmp);
                // 先写临时文件，成功后再原子改名，避免续跑时把半截文件当成已完成
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target.toAbsolutePath().toString();
            } catch (Exception e) {
                last = e;
                System.out.println("[Grok] 片段 " + segIndex + " 第 " + (a + 1) + " 次尝试失败：" + e.getMessage());
                try { Files.deleteIfExists(tmp); } catch (Exception ignore) { }
                if (a < attempts - 1) {
                    long backoff = 5000L * (a + 1);
                    System.out.println("[Grok] " + (backoff / 1000) + "s 后重试…");
                    Thread.sleep(backoff);
                }
            }
        }
        throw last != null ? last : new RuntimeException("片段 " + segIndex + " Grok 出片失败");
    }

    private String submit(String visualPrompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("prompt", visualPrompt);
        // reference_images 必须是对象数组：[{"url":"..."},...]（官方要求，裸字符串数组会 422）
        if (referenceImageUrls != null && !referenceImageUrls.isEmpty()) {
            List<Map<String,String>> imgs = new ArrayList<>();
            for (String u : referenceImageUrls) imgs.add(Map.of("url", u));
            body.put("reference_images", imgs);
        }
        // reference_audios：Grok 内置预设人声，按 voice_id 引用（最多 3 个）；与 reference_images 可并存，不能与 image 共存
        if (referenceAudioIds != null && !referenceAudioIds.isEmpty()) {
            List<Map<String,String>> audios = new ArrayList<>();
            for (String id : referenceAudioIds) audios.add(Map.of("voice_id", id));
            body.put("reference_audios", audios);
        }
        body.put("duration", seconds);
        body.put("aspect_ratio", "16:9");
        body.put("resolution", resolution);   // Grok 支持：1080p/720p/480p（默认480p），yml size 控制

        String jsonBody = mapper.writeValueAsString(body);
        String maskedKey = (apiKey == null || apiKey.isEmpty())
                ? "<空>" : apiKey.substring(0, Math.min(6, apiKey.length())) + "…";
        System.out.println("[Grok DEBUG] POST " + baseUrl + "/v1/videos/generations");
        System.out.println("[Grok DEBUG] Authorization: Bearer " + maskedKey);
        System.out.println("[Grok DEBUG] reference_images 张数=" + (referenceImageUrls == null ? 0 : referenceImageUrls.size()));
        System.out.println("[Grok DEBUG] reference_audios 个=" + (referenceAudioIds == null ? 0 : referenceAudioIds.size()));
        System.out.println("[Grok DEBUG] request body=\n" + jsonBody);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/videos/generations"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("提交 Grok 任务 HTTP " + resp.statusCode() + "：" + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        // 兼容中转站（如 sub.xlcsh.icu）用 new-api 格式包裹：{"code":0,"data":{"request_id":...}}
        JsonNode rid = root.get("request_id");
        if (rid == null || rid.asText().isEmpty()) {
            JsonNode data = root.get("data");
            if (data != null) rid = data.get("request_id");
        }
        if (rid == null || rid.asText().isEmpty()) {
            throw new RuntimeException("提交成功但未找到 request_id，响应：" + resp.body());
        }
        return rid.asText();
    }

    private String waitForCompletion(String requestId) throws Exception {
        // 超时随视频时长自适应：约 seconds×12 次轮询（3s/次），下限 60 次兜底
        int maxTries = Math.max(60, seconds * 12);
        for (int i = 0; i < maxTries; i++) {
            String pollUrl = baseUrl + "/v1/videos/" + requestId;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(pollUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Grok POLL DEBUG #" + (i + 1) + "] GET " + pollUrl);
            System.out.println("[Grok POLL DEBUG #" + (i + 1) + "] HTTP " + resp.statusCode());
            System.out.println("[Grok POLL DEBUG #" + (i + 1) + "] body=\n" + resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("查询 Grok 任务 HTTP " + resp.statusCode() + "：" + resp.body());
            }
            JsonNode root = mapper.readTree(resp.body());
            // 兼容中转站 new-api 包裹：真实结果在 root.data 下
            JsonNode payload = root.has("data") && root.get("data").isObject() ? root.get("data") : root;
            String status = payload.has("status") ? payload.get("status").asText("") : "";
            String s = status.toLowerCase();
            if (s.contains("fail") || s.contains("expired")) {
                JsonNode err = payload.get("error");
                String msg = (err != null && !err.asText().isEmpty()) ? err.asText() : resp.body();
                throw new RuntimeException("Grok 视频失败，status=" + status + " 响应=" + msg);
            }
            if ("done".equals(s)) {
                JsonNode video = payload.get("video");
                if (video != null) {
                    // 官方直连时 video.url 是完整 URL；中转站返回相对路径需补全 baseUrl
                    if (video.has("url")) {
                        String url = video.get("url").asText();
                        if (url != null && !url.isEmpty() && !url.startsWith("http")) {
                            url = baseUrl + url;   // 中转站返回 /v1/videos/{id}/content → 补全为完整下载地址
                        }
                        return url;
                    }
                }
            }
            Thread.sleep(3000);
        }
        throw new RuntimeException("Grok 视频超时（约 " + (maxTries * 3 / 60) + " 分钟仍未完成）");
    }

    private void download(String videoUrl, Path target) throws Exception {
        System.out.println("[Grok DOWNLOAD DEBUG] GET " + videoUrl);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(videoUrl))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("[Grok DOWNLOAD DEBUG] HTTP " + resp.statusCode()
                + " content-type=" + resp.headers().firstValue("Content-Type").orElse("?")
                + " bytes=" + resp.body().length);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("下载 Grok 视频失败 HTTP " + resp.statusCode());
        }
        byte[] data = resp.body();
        // 防御：若 /content 返回的是 JSON（含真正 mp4 地址），再解析一级后二次下载
        String ct = resp.headers().firstValue("Content-Type").orElse("");
        if (ct.contains("json") || (data.length > 0 && data[0] == '{')) {
            try {
                JsonNode j = mapper.readTree(data);
                String real = j.has("url") ? j.get("url").asText("")
                        : (j.has("video_url") ? j.get("video_url").asText("") : "");
                if (real != null && !real.isEmpty() && !real.startsWith("http")) real = baseUrl + real;
                if (!real.isEmpty()) {
                    System.out.println("[Grok DOWNLOAD DEBUG] 检测到 JSON 包裹，二次下载：" + real);
                    HttpRequest req2 = HttpRequest.newBuilder()
                            .uri(URI.create(real))
                            .header("Authorization", "Bearer " + apiKey)
                            .GET()
                            .build();
                    HttpResponse<byte[]> resp2 = http.send(req2, HttpResponse.BodyHandlers.ofByteArray());
                    if (resp2.statusCode() != 200) {
                        throw new RuntimeException("二次下载 Grok 视频失败 HTTP " + resp2.statusCode());
                    }
                    data = resp2.body();
                }
            } catch (Exception e) {
                // 不是 JSON，按原始字节处理
            }
        }
        Files.write(target, data);
    }
}
