package com.view163.digitalhuman.web;

import com.view163.digitalhuman.config.AppProperties;
import com.view163.digitalhuman.prompt.EnrichPrompt;
import com.view163.digitalhuman.service.GrokVideoGenService;
import com.view163.digitalhuman.service.PerformanceEnricher;
import com.view163.digitalhuman.service.ScriptGenerator;
import com.view163.digitalhuman.service.ScriptSlicer;
import com.view163.digitalhuman.service.VideoGenService;
import com.view163.digitalhuman.service.VideoGenerator;
import com.view163.digitalhuman.service.VolcengineVideoGenService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 向导式出片接口（产品化主线）：出片的每一步主动权都在用户手里。
 *
 *   第1步 POST /api/wizard/draft  {topic, persona}
 *        → {taskId, script}                     RAG 生成裸口播稿，用户在页面上确认/编辑
 *   第2步 POST /api/wizard/slice  {taskId, script, seconds}
 *        → {segments:[{index,text,chars}]}      按用户选的 5-30s 动态切片，用户确认分片
 *   第3步 POST /api/wizard/enrich {taskId, index, segmentText}
 *        → {visualPrompt, enriched}             逐段富化，用户逐段确认表演文本
 *   第4步 POST /api/wizard/video  {taskId, index, segmentText, visualPrompt}
 *        → 异步出片单段；GET /api/wizard/video/{taskId}/{index} 轮询结果
 *
 * 设计要点：
 *  - 不做自动拼接：每段是独立成片，主动权交给用户（后续可加手动拼接接口）。
 *  - 会话状态在内存（ConcurrentHashMap）：本地单人测试足够；重启丢失，重头再来即可。
 *  - Draft 同步调用（RAG 建库+写稿可能 1-2 分钟）；Video 异步（引擎轮询分钟级）。
 *  - 每个任务的成片落在 output/{taskId}/seg_XX.mp4，经 WebConfig 的 /output/** 直接播放。
 */
@RestController
@CrossOrigin(origins = "*") // 主页部署在 GitHub Pages，与后端不同源；挂 ECS 后可收紧为具体域名
public class WizardController {

    private final ScriptGenerator generator;
    private final PerformanceEnricher enricher;
    private final ScriptSlicer slicer;
    private final AppProperties props;
    private final EnrichPrompt enrichPrompt;

    /** 视频出片线程池：用户可同时点几段生成，3 并发足够（方舟侧本来就是异步任务） */
    private final ExecutorService videoPool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "wizard-video");
        t.setDaemon(true);
        return t;
    });

    /** 向导会话：taskId → 状态 */
    private final Map<String, WizardSession> sessions = new ConcurrentHashMap<>();
    /** 视频任务：taskId + "#" + segIndex → 状态 */
    private final Map<String, SegmentJob> videoJobs = new ConcurrentHashMap<>();

    public WizardController(ScriptGenerator generator, PerformanceEnricher enricher,
                            ScriptSlicer slicer, AppProperties props, EnrichPrompt enrichPrompt) {
        this.generator = generator;
        this.enricher = enricher;
        this.slicer = slicer;
        this.props = props;
        this.enrichPrompt = enrichPrompt;
    }

    /* ────────────── 第1步：裸口播稿 ────────────── */

    @PostMapping("/api/wizard/draft")
    public Map<String, Object> draft(@RequestBody Map<String, String> body) {
        String topic = body.getOrDefault("topic", "").trim();
        String persona = normalizePersona(body.get("persona"));
        if (topic.isEmpty()) return Map.of("error", "topic 不能为空");
        try {
            // 注意：知识库是有状态单例，并发写稿会互相污染；本地单人测试可接受
            String docsDir = props.getDocsDir() + "/" + persona;
            generator.buildKnowledgeBase(docsDir);
            String script = generator.generate(topic, persona);
            String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            WizardSession s = new WizardSession(persona);
            s.script = script;
            sessions.put(taskId, s);
            return Map.of("taskId", taskId, "script", script);
        } catch (Exception e) {
            return Map.of("error", "写稿失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /* ────────────── 第2步：切片（5-30s 动态） ────────────── */

    @PostMapping("/api/wizard/slice")
    public Map<String, Object> slice(@RequestBody Map<String, Object> body) {
        String taskId = str(body.get("taskId"));
        WizardSession s = sessions.get(taskId);
        String script = str(body.get("script"));
        // 手动输入文案时没有会话 → 自动创建（默认 anuo）
        boolean created = false;
        if (s == null) {
            if (script.isEmpty()) return Map.of("error", "会话不存在或已过期，请重新生成裸稿");
            taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            s = new WizardSession("anuo");
            sessions.put(taskId, s);
            created = true;
        }
        if (script.isEmpty()) script = s.script;
        int seconds = intVal(body.get("seconds"), s.seconds);
        seconds = Math.max(5, Math.min(30, seconds));   // 前端承诺 5-30，后端再夹一次

        List<String> segments = slicer.slice(script, seconds);
        s.script = script;
        s.seconds = seconds;
        s.segments = segments;

        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i + 1);
            m.put("text", segments.get(i));
            m.put("chars", segments.get(i).length());
            list.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("seconds", seconds);
        result.put("segments", list);
        return result;
    }

    /* ────────────── 第3步：逐段富化 ────────────── */

    @PostMapping("/api/wizard/enrich")
    public Map<String, Object> enrich(@RequestBody Map<String, Object> body) {
        String taskId = str(body.get("taskId"));
        WizardSession s = sessions.get(taskId);
        if (s == null) return Map.of("error", "会话不存在或已过期");
        int index = intVal(body.get("index"), 0);
        if (index < 1) return Map.of("error", "index 必须从 1 开始");
        String segmentText = str(body.get("segmentText"));
        if (segmentText.isEmpty()) {
            if (s.segments != null && index <= s.segments.size()) segmentText = s.segments.get(index - 1);
            else return Map.of("error", "segmentText 不能为空");
        }

        try {
            String personaName = "anuo".equals(s.persona) ? "阿诺" : "老潘";
            String prevTail = (index > 1 && s.segments != null && index - 2 < s.segments.size())
                    ? tailOf(s.segments.get(index - 2)) : "";
            String segContext = buildSegmentContext(index - 1, totalSegments(s), segmentText, prevTail);
            String enriched = enricher.enrich(segContext, personaName, s.persona);
            String visual = enrichPrompt.extractVisual(enriched);
            return Map.of("index", index, "visualPrompt", visual, "enriched", enriched);
        } catch (Exception e) {
            return Map.of("error", "富化失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /* ────────────── 第4步：单段出片（用户主动触发，不拼接） ────────────── */

    @PostMapping("/api/wizard/video")
    public Map<String, Object> video(@RequestBody Map<String, Object> body) {
        String taskId = str(body.get("taskId"));
        WizardSession s = sessions.get(taskId);
        if (s == null) return Map.of("error", "会话不存在或已过期");
        int index = intVal(body.get("index"), 0);
        if (index < 1) return Map.of("error", "index 必须从 1 开始");
        String segmentText = str(body.get("segmentText"));
        String visualPrompt = str(body.get("visualPrompt"));
        if (segmentText.isEmpty() || visualPrompt.isEmpty()) {
            return Map.of("error", "segmentText 与 visualPrompt 都不能为空（先完成第3步富化）");
        }
        if (index > 30) return Map.of("error", "index 过大");

        String jobKey = taskId + "#" + index;
        SegmentJob job = videoJobs.computeIfAbsent(jobKey, k -> new SegmentJob());
        synchronized (job) {
            if ("running".equals(job.status)) {
                return Map.of("submitted", true, "note", "该片段已在生成中");
            }
            job.status = "running";
            job.videoUrl = null;
            job.error = null;
        }

        final String fSegment = segmentText;
        final String fVisual = visualPrompt;
        final int fIndex = index;
        final Path taskDir = Paths.get(props.getVideo().getOutputDir(), taskId);
        videoPool.submit(() -> runSegmentVideo(job, s, taskId, fIndex, fSegment, fVisual, taskDir));
        return Map.of("submitted", true);
    }

    /** 查询单段出片状态：GET /api/wizard/video/{taskId}/{index} */
    @GetMapping("/api/wizard/video/{taskId}/{index}")
    public Map<String, Object> videoStatus(@PathVariable String taskId, @PathVariable int index) {
        SegmentJob job = videoJobs.get(taskId + "#" + index);
        if (job == null) return Map.of("status", "not_found");
        if (job.error != null) return Map.of("status", job.status, "error", job.error);
        if (job.videoUrl != null) return Map.of("status", job.status, "videoUrl", job.videoUrl);
        return Map.of("status", job.status);
    }

    /** 单段出片：组装 prompt → 引擎（按会话 persona + 切片秒数 + 任务独立目录）→ 下载落盘 */
    private void runSegmentVideo(SegmentJob job, WizardSession s, String taskId,
                                 int index, String segmentText, String visualPrompt, Path taskDir) {
        try {
            Files.createDirectories(taskDir);
            String prompt = visualPrompt + "\n\n[台词]\n" + segmentText;
            VideoGenerator engine = newVideoGenerator(s.persona, s.seconds, taskDir.toString());
            String mp4 = engine.generate(prompt, index, totalSegments(s));
            String fileName = Paths.get(mp4).getFileName().toString();
            job.videoUrl = "/output/" + taskId + "/" + fileName;
            job.status = "done";
            System.out.println("[Wizard " + taskId + "] 片段 " + index + " 出片完成：" + mp4);
        } catch (Exception e) {
            job.status = "failed";
            job.error = e.getMessage() == null ? e.toString() : e.getMessage();
            System.out.println("[Wizard " + taskId + "] 片段 " + index + " 出片失败：" + job.error);
        }
    }

    /** 引擎工厂：向导版——时长用会话切片秒数，输出目录用任务独立子目录 */
    private VideoGenerator newVideoGenerator(String persona, int seconds, String outputDir) {
        String provider = props.getVideo().getProvider();
        if ("grok".equalsIgnoreCase(provider)) return new GrokVideoGenService(props, persona, seconds, outputDir);
        if ("volcengine".equalsIgnoreCase(provider)) return new VolcengineVideoGenService(props, persona, seconds, outputDir);
        return new VideoGenService(props, persona, seconds, outputDir);
    }

    /* ────────────── 工具 ────────────── */

    private static String normalizePersona(String p) {
        String v = p == null ? "" : p.trim().toLowerCase();
        if ("pan".equals(v) || "kaka".equals(v) || "samuel".equals(v)) return v;
        return "anuo";
    }

    private static int totalSegments(WizardSession s) {
        return s.segments == null ? 1 : s.segments.size();
    }

    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }

    private static int intVal(Object o, int def) {
        try { return o == null ? def : Integer.parseInt(o.toString()); }
        catch (NumberFormatException e) { return def; }
    }

    private static String buildSegmentContext(int index, int total, String seg, String prevTail) {
        StringBuilder sb = new StringBuilder();
        sb.append("【分段提示】这是口播稿的第 ").append(index + 1).append(" 段，共 ").append(total).append(" 段。");
        if (index > 0 && prevTail != null && !prevTail.isEmpty()) {
            sb.append("上一段结尾是：\"").append(prevTail).append("\"，本段画面与语气需自然承接上文，不要重复开场白。");
        } else {
            sb.append("这是开篇第一段，按正常开场处理。");
        }
        sb.append("\n【本段口播稿】\n").append(seg);
        return sb.toString();
    }

    private static String tailOf(String seg) {
        if (seg == null || seg.isEmpty()) return "";
        int start = Math.max(0, seg.length() - 15);
        return seg.substring(start);
    }

    /* ────────────── 会话与任务状态 ────────────── */

    static final class WizardSession {
        final String persona;
        String script = "";
        int seconds = 15;
        List<String> segments;
        WizardSession(String persona) { this.persona = persona; }
    }

    static final class SegmentJob {
        volatile String status = "init";
        volatile String videoUrl;
        volatile String error;
    }
}
