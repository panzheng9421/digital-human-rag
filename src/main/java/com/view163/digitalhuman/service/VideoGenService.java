package com.view163.digitalhuman.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.view163.digitalhuman.config.AppProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Stage3 出片：按 New API 协议调用 new.xlcsh.top 中转站的 Seedance 2.5 视频生成。
 * 协议详见 rag/API.md。
 * 流程：POST /v1/videos（多参考图 input_reference + metadata.audio） -> GET /v1/videos/{id} 轮询 -> 下载 MP4。
 * 说明：New API 的 Seedance 渠道输出比例固定 16:9 横屏，size 字符串不切换横竖屏（Vlog 横屏正好）。
 */
@Service
public class VideoGenService {

    private final String baseUrl;
    private final String apiKey;
    private final String modelId;
    private final List<String> referenceImageUrls;
    private final int seconds;
    private final String size;
    private final String outputDir;
    private final int retryAttempts;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    // 中转站 new.xlcsh.top 的证书不被 JDK 默认信任库识别，这里构建「信任所有证书 + 关闭主机名校验」的专用客户端，
    // 仅用于该中继请求，避免 SSLHandshakeException(PKIX path building failed)。
    private final HttpClient http = createTrustAllHttpClient();

    public VideoGenService(AppProperties props) {
        AppProperties.Video v = props.getVideo();
        this.baseUrl = v.getRelayBaseUrl();
        this.apiKey = v.getRelayApiKey();
        this.modelId = v.getModelId();
        this.referenceImageUrls = v.getReferenceImageUrls() == null ? List.of() : v.getReferenceImageUrls();
        this.seconds = v.getSeconds();
        this.size = v.getSize();
        this.outputDir = v.getOutputDir();
        this.retryAttempts = Math.max(0, v.getRetryAttempts());
    }

    /**
     * 完整流程（单切片，带重试 + 确定性命名）：提交 -> 轮询 -> 下载 -> 落盘 seg_XX.mp4。
     * segIndex>0 时用确定性文件名（支持断点续跑）；segIndex<=0 时回退随机名。
     * 失败时按 retryAttempts 重试（指数退避），全部失败才向上抛，由调用方决定是否跳过该切片。
     */
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
                System.out.println("[视频] 片段 " + segIndex + "/" + total + " 已提交任务：" + taskId
                        + (attempts > 1 ? "（第 " + (a + 1) + "/" + attempts + " 次尝试）" : ""));
                String videoUrl = waitForCompletion(taskId);
                System.out.println("[视频] 片段 " + segIndex + " 生成完成，下载中…");
                download(videoUrl, tmp);
                // 先写临时文件，成功后再原子改名，避免续跑时把半截文件当成已完成
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target.toAbsolutePath().toString();
            } catch (Exception e) {
                last = e;
                System.out.println("[视频] 片段 " + segIndex + " 第 " + (a + 1) + " 次尝试失败：" + e.getMessage());
                try { Files.deleteIfExists(tmp); } catch (Exception ignore) { }
                if (a < attempts - 1) {
                    long backoff = 5000L * (a + 1);
                    System.out.println("[视频] 片段 " + segIndex + " " + (backoff / 1000) + "s 后重试…");
                    Thread.sleep(backoff);
                }
            }
        }
        throw last != null ? last : new RuntimeException("片段 " + segIndex + " 出片失败");
    }

    private String submit(String visualPrompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("prompt", visualPrompt);
        if (referenceImageUrls != null && !referenceImageUrls.isEmpty()) {
            body.put("input_reference", referenceImageUrls);
        }
        body.put("seconds", String.valueOf(seconds));
        body.put("size", size);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("audio", true);
        body.put("metadata", metadata);

        String jsonBody = mapper.writeValueAsString(body);
        // === DEBUG：打印实际发给 Seedance 的请求参数，核对字段是否齐全 ===
        String maskedKey = (apiKey == null || apiKey.isEmpty())
                ? "<空>" : apiKey.substring(0, Math.min(6, apiKey.length())) + "…(len=" + apiKey.length() + ")";
        System.out.println("[DEBUG] POST " + baseUrl);
        System.out.println("[DEBUG] Authorization: Bearer " + maskedKey);
        System.out.println("[DEBUG] input_reference 张数=" + (referenceImageUrls == null ? 0 : referenceImageUrls.size()));
        if (referenceImageUrls != null) {
            for (int i = 0; i < referenceImageUrls.size(); i++) {
                System.out.println("[DEBUG]   ref[" + i + "]=" + referenceImageUrls.get(i));
            }
        }
        System.out.println("[DEBUG] request body=\n" + jsonBody);
        // =============================================================

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("提交视频任务 HTTP " + resp.statusCode() + "：" + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        if (isError(root)) {
            throw new RuntimeException("提交视频任务失败：" + resp.body());
        }
        String taskId = str(root, "id", "task_id");
        if (taskId == null && root.get("data") != null) {
            taskId = str(root.get("data"), "id", "task_id");
        }
        if (taskId == null) {
            throw new RuntimeException("提交成功但未找到任务ID，原始响应：" + resp.body());
        }
        return taskId;
    }

    private String waitForCompletion(String taskId) throws Exception {
        String url = baseUrl + "/" + taskId;
        // 超时随视频时长自适应：约 seconds×12 次轮询（3s/次 ≈ seconds×36s），下限 60 次兜底 5s 片段
        int maxTries = seconds <= 0 ? 400 : Math.max(60, seconds * 12);
        for (int i = 0; i < maxTries; i++) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new RuntimeException("查询视频任务 HTTP " + resp.statusCode() + "：" + resp.body());
            }
            JsonNode root = mapper.readTree(resp.body());
            if (isError(root)) {
                throw new RuntimeException("查询视频任务失败：" + resp.body());
            }
            JsonNode data = root.get("data") != null ? root.get("data") : root;
            String status = str(data, "status");
            if (status != null) {
                String s = status.toLowerCase();
                if (s.contains("fail")) {
                    JsonNode err = data.get("error");
                    String msg = (err != null && !err.asText().isEmpty()) ? err.asText() : resp.body();
                    throw new RuntimeException("视频生成失败，状态=" + status + " 响应=" + msg);
                }
                if (s.contains("succeed") || s.contains("success") || s.contains("completed")) {
                    String videoUrl = findVideoUrl(data);
                    if (videoUrl == null) videoUrl = findVideoUrl(root);
                    if (videoUrl != null) {
                        return videoUrl;
                    }
                }
            }
            Thread.sleep(3000);
        }
        throw new RuntimeException("视频生成超时（约 " + (maxTries * 3 / 60) + " 分钟仍未完成）");
    }

    private String findVideoUrl(JsonNode data) {
        String[] paths = {"video_url", "url", "metadata.url", "output", "metadata.video_url"};
        for (String p : paths) {
            JsonNode n = data;
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
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(videoUrl))
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("下载视频失败 HTTP " + resp.statusCode());
        }
        Files.write(target, resp.body());
    }

    /** 判断 New API 响应是否为错误：含 error 字段 / code 为错误串 => 失败 */
    private boolean isError(JsonNode root) {
        if (root == null) return true;
        if (root.has("error")) return true;
        JsonNode code = root.get("code");
        if (code != null) {
            if (code.isNumber() && code.asInt() == 0) return false;
            String c = code.asText();
            if ("0".equals(c) || "success".equalsIgnoreCase(c)) return false;
            return true;
        }
        return false;
    }

    private String str(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode n = node.get(k);
            if (n != null && !n.asText().isEmpty()) return n.asText();
        }
        return null;
    }

    /** 构建一个信任所有证书的 HttpClient（跳过证书链与主机名校验），仅用于 new.xlcsh.top 中转站。 */
    private static HttpClient createTrustAllHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }
                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }
                    }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            // 关闭端点主机名识别，避免自签/域名不匹配触发校验失败
            SSLParameters sslParams = new SSLParameters();
            sslParams.setEndpointIdentificationAlgorithm(null);
            return HttpClient.newBuilder()
                    .sslContext(ctx)
                    .sslParameters(sslParams)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("构建信任所有证书的 HttpClient 失败", e);
        }
    }
}
