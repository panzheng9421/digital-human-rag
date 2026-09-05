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
 * Stage3 出片（火山方舟 Seedance 2.5 官方直连）：调用字节火山方舟 doubao-seedance-2-5 模型。
 * 文档：火山方舟「创建视频生成任务」API
 *   POST https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks
 *   GET  https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/{id}
 * 流程：提交异步任务 -> 轮询状态 -> 取 video_url -> 下载 MP4。
 *
 * 关键约束（来自官方文档）：
 *   - 异步接口：提交返回任务 id，需再调「查询视频生成任务」轮询，成功后取 video_url
 *   - 任务 id 仅保存 7 天；默认超时 48h（execution_expires_after 可设 [3600, 259200]）
 *   - duration 支持到 30s（2.5 模型）；resolution 支持 480p/720p/1080p/4k
 *   - ratio 支持 16:9、4:3、1:1、3:4、9:16、21:9、adaptive
 *   - generate_audio：是否由模型生成配音（布尔）；也可在 content[] 里传 audio_url 指定参考音频
 *
 * 真人肖像（已授权素材通道）：
 *   doubao-seedance-2-5 不允许直接上传「含真人人脸的外部 URL」参考图，会拦截。
 *   正确做法：先在方舟「体验中心 → 我的 → 真人人像 → 管理素材」录入真人素材，
 *   由本人扫码完成真人认证并授权，拿到 asset_id；调用时把参考图 URL 写成
 *   asset://<asset_id> 传入（url 字段直接支持，代码结构无需改动）。
 *   普通非人脸参考图（如场景背景 bedroom-night.png）仍可传外部 http(s) URL。
 *   详见：https://www.volcengine.com/docs/82379/2315856（录入真人形象素材）
 *
 * 真人音色（参考音频，同通道）：
 *   在 content[] 追加 {type:audio_url, audio_url:{url:"asset://<audio_asset_id>"}, role:reference_audio}
 *   即可锁定真人音色+口型；音频素材 ID 同样从方舟「素材&虚拟人像库」获取（asset:// 格式）。
 *   见官方「创建视频生成任务」文档：https://www.volcengine.com/docs/6390/1393047
 */
public class VolcengineVideoGenService implements VideoGenerator {

    private final String apiKey;
    private final String modelId;
    private final List<String> referenceImageUrls;
    private final List<String> referenceAudioUrls;
    private final int seconds;
    private final String resolution;   // 480p/720p/1080p/4k
    private final String ratio;        // 16:9 等
    private final boolean generateAudio;
    private final String outputDir;
    private final int retryAttempts;
    private final String baseUrl;      // 火山方舟 API 基地址，勿带末尾斜杠
    private final String taskEndpoint; // 完整提交地址 = baseUrl + /api/v3/contents/generations/tasks

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    // 火山方舟使用标准证书链，无需 trust-all 客户端
    private final HttpClient http = HttpClient.newHttpClient();

    public VolcengineVideoGenService(AppProperties props, String persona) {
        AppProperties.Video v = props.getVideo();
        AppProperties.Video.Volcengine volc = v.getVolcengine();
        String envKey = System.getenv("ARK_API_KEY");
        this.apiKey = (volc.getApiKey() != null && !volc.getApiKey().isEmpty())
                ? volc.getApiKey() : (envKey != null ? envKey : "");
        this.modelId = volc.getModelId();   // 默认 doubao-seedance-2-5
        this.referenceImageUrls = v.getReferenceImageUrlsFor(persona);
        this.referenceAudioUrls = v.getReferenceAudioUrlsFor(persona);
        this.seconds = v.getSeconds();
        this.resolution = toResolution(v.getSize());
        this.ratio = v.getRatio();
        this.generateAudio = v.isGenerateAudio();
        this.outputDir = v.getOutputDir();
        this.retryAttempts = Math.max(0, v.getRetryAttempts());
        String cfgBase = volc.getBaseUrl();
        this.baseUrl = (cfgBase != null && !cfgBase.isEmpty()) ? cfgBase : "https://ark.cn-beijing.volces.com";
        this.taskEndpoint = this.baseUrl + "/api/v3/contents/generations/tasks";
        System.out.println("[Volc INIT] baseUrl=" + this.baseUrl + " model=" + modelId
                + " seconds=" + this.seconds + " resolution=" + this.resolution + " ratio=" + this.ratio
                + " generateAudio=" + this.generateAudio + " refImages=" + this.referenceImageUrls.size()
                + " refAudio=" + this.referenceAudioUrls.size()
                + " retries=" + this.retryAttempts);
    }

    private static String toResolution(String size) {
        if (size == null || size.isEmpty()) return "720p";
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
                String taskId = submit(visualPrompt);
                System.out.println("[Volc] 片段 " + segIndex + "/" + total + " 已提交任务：" + taskId
                        + (attempts > 1 ? "（第 " + (a + 1) + "/" + attempts + " 次尝试）" : ""));
                String videoUrl = waitForCompletion(taskId);
                System.out.println("[Volc] 片段 " + segIndex + " 生成完成，下载中…");
                download(videoUrl, tmp);
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target.toAbsolutePath().toString();
            } catch (Exception e) {
                last = e;
                System.out.println("[Volc] 片段 " + segIndex + " 第 " + (a + 1) + " 次尝试失败：" + e.getMessage());
                try { Files.deleteIfExists(tmp); } catch (Exception ignore) { }
                if (a < attempts - 1) {
                    long backoff = 5000L * (a + 1);
                    System.out.println("[Volc] " + (backoff / 1000) + "s 后重试…");
                    Thread.sleep(backoff);
                }
            }
        }
        throw last != null ? last : new RuntimeException("片段 " + segIndex + " 火山方舟出片失败");
    }

    private String submit(String visualPrompt) throws Exception {
        // 构造 content[]：text 提示 + 参考图（role=reference_image）
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textItem = new LinkedHashMap<>();
        textItem.put("type", "text");
        textItem.put("text", visualPrompt);
        content.add(textItem);

        if (referenceImageUrls != null && !referenceImageUrls.isEmpty()) {
            for (String url : referenceImageUrls) {
                Map<String, Object> img = new LinkedHashMap<>();
                img.put("type", "image_url");
                // 方舟要求 image_url 是对象：{"url": "..."}，asset:// 与外部 URL 都放这里
                Map<String, Object> iu = new LinkedHashMap<>();
                iu.put("url", url);
                img.put("image_url", iu);
                img.put("role", "reference_image");
                content.add(img);
            }
        }

        // 参考音频（方舟专用）：content[] 里追加 type=audio_url / role=reference_audio 的 item，
        // audio_url.url 支持 asset:// 音频素材ID / 公网URL / Base64。用于锁定真人音色（口型+声音）。
        if (referenceAudioUrls != null && !referenceAudioUrls.isEmpty()) {
            for (String url : referenceAudioUrls) {
                Map<String, Object> aud = new LinkedHashMap<>();
                aud.put("type", "audio_url");
                Map<String, Object> au = new LinkedHashMap<>();
                au.put("url", url);
                aud.put("audio_url", au);
                aud.put("role", "reference_audio");
                content.add(aud);
            }
        }

        // 有参考音频时必须出声，否则静音片白传；无音频时沿用配置开关
        boolean effectiveGenerateAudio = (!referenceAudioUrls.isEmpty()) || generateAudio;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("content", content);
        body.put("duration", seconds);
        body.put("resolution", resolution);
        body.put("ratio", ratio);
        body.put("generate_audio", effectiveGenerateAudio);

        String jsonBody = mapper.writeValueAsString(body);
        String maskedKey = (apiKey == null || apiKey.isEmpty())
                ? "<空>" : apiKey.substring(0, Math.min(6, apiKey.length())) + "…";
        System.out.println("[Volc DEBUG] POST " + taskEndpoint);
        System.out.println("[Volc DEBUG] Authorization: Bearer " + maskedKey);
        System.out.println("[Volc DEBUG] content 元素数=" + content.size());
        System.out.println("[Volc DEBUG] generate_audio(effective)=" + effectiveGenerateAudio
                + " (config=" + generateAudio + ", refAudio=" + referenceAudioUrls.size() + ")");
        System.out.println("[Volc DEBUG] request body=\n" + jsonBody);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(taskEndpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("[Volc DEBUG] HTTP " + resp.statusCode());
        System.out.println("[Volc DEBUG] response body=\n" + resp.body());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("提交火山方舟任务 HTTP " + resp.statusCode() + "：" + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode id = root.get("id");
        if (id == null || id.asText().isEmpty()) {
            JsonNode data = root.get("data");
            if (data != null) id = data.get("id");
        }
        if (id == null || id.asText().isEmpty()) {
            throw new RuntimeException("提交成功但未找到任务 id，响应：" + resp.body());
        }
        return id.asText();
    }

    private String waitForCompletion(String taskId) throws Exception {
        String url = baseUrl + "/api/v3/contents/generations/tasks/" + taskId;
        int maxTries = Math.max(60, seconds * 12);
        for (int i = 0; i < maxTries; i++) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[Volc POLL DEBUG #" + (i + 1) + "] GET " + url);
            System.out.println("[Volc POLL DEBUG #" + (i + 1) + "] HTTP " + resp.statusCode());
            System.out.println("[Volc POLL DEBUG #" + (i + 1) + "] body=\n" + resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("查询火山方舟任务 HTTP " + resp.statusCode() + "：" + resp.body());
            }
            JsonNode root = mapper.readTree(resp.body());
            String status = root.has("status") ? root.get("status").asText("") : "";
            String s = status.toLowerCase();
            if (s.contains("fail") || s.contains("expired")) {
                JsonNode err = root.get("error");
                String msg = (err != null && !err.asText().isEmpty()) ? err.asText() : resp.body();
                throw new RuntimeException("火山方舟视频失败，status=" + status + " 响应=" + msg);
            }
            if ("succeeded".equals(s)) {
                // 成功时视频地址在 data.video_url（部分版本在根级）
                String videoUrl = findVideoUrl(root);
                if (videoUrl != null) return videoUrl;
            }
            Thread.sleep(3000);
        }
        throw new RuntimeException("火山方舟视频超时（约 " + (maxTries * 3 / 60) + " 分钟仍未完成）");
    }

    private String findVideoUrl(JsonNode root) {
        // 方舟 2.5 把视频地址放在 content.video_url（content 对象内），优先匹配；其余为兼容旧/其它结构
        String[] paths = {"content.video_url", "content.url", "video_url", "data.video_url", "url", "data.url"};
        for (String p : paths) {
            JsonNode n = root;
            for (String seg : p.split("\\.")) {
                if (n == null) break;
                n = n.get(seg);
            }
            if (n != null && !n.asText().isEmpty()) {
                return n.asText();
            }
        }
        return null;
    }

    private void download(String videoUrl, Path target) throws Exception {
        System.out.println("[Volc DOWNLOAD DEBUG] GET " + videoUrl);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(videoUrl))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("[Volc DOWNLOAD DEBUG] HTTP " + resp.statusCode()
                + " content-type=" + resp.headers().firstValue("Content-Type").orElse("?")
                + " bytes=" + resp.body().length);
        if (resp.statusCode() != 200) {
            throw new RuntimeException("下载火山方舟视频失败 HTTP " + resp.statusCode());
        }
        Files.write(target, resp.body());
    }
}
