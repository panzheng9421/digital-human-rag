package com.view163.digitalhuman.web;

import com.view163.digitalhuman.config.AppProperties;
import com.view163.digitalhuman.prompt.EnrichPrompt;
import com.view163.digitalhuman.service.GrokVideoGenService;
import com.view163.digitalhuman.service.PerformanceEnricher;
import com.view163.digitalhuman.service.ScriptGenerator;
import com.view163.digitalhuman.service.ScriptSlicer;
import com.view163.digitalhuman.service.VideoConcatenator;
import com.view163.digitalhuman.service.VideoGenService;
import com.view163.digitalhuman.service.VideoGenerator;
import com.view163.digitalhuman.service.VolcengineVideoGenService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线出片 HTTP 接口（供项目主页「在线体验」区调用）。
 *
 * 前端约定（docs/index.html 已按此实现）：
 *   POST /api/generate   body: {"topic": "...", "persona": "anuo|pan"}
 *                        → 返回 {"taskId": "..."}
 *   GET  /api/task/{id}  → {"status": "running"|"done"|"failed"|"not_found",
 *                           "videoUrl": "/output/{taskId}/final.mp4", "error": "..."}
 * 成片通过 WebConfig 映射的 /output/** 静态目录直接播放/下载。
 *
 * 设计说明：
 *  - ScriptGenerator 的知识库是有状态单例（buildKnowledgeBase 会重建内部索引），
 *    因此任务用单线程池串行执行，避免并发任务互相污染知识库。本地/单人测试足够。
 *  - 每个任务独立子目录 output/{taskId}/，seg 文件确定性命名，任务重跑可续跑。
 *  - VideoGenerator 按 persona 每任务现建（三个引擎构造器都接受 persona 覆盖）。
 */
@RestController
@CrossOrigin(origins = "*") // 主页部署在 GitHub Pages，域名与后端不同源；上线 ECS 后可收紧为具体域名
public class GenerateController {

    private final ScriptGenerator generator;
    private final PerformanceEnricher enricher;
    private final ScriptSlicer slicer;
    private final VideoConcatenator concatenator;
    private final AppProperties props;
    private final EnrichPrompt enrichPrompt;

    /** 单线程串行执行：知识库有状态，不并发 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-task");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();

    public GenerateController(ScriptGenerator generator, PerformanceEnricher enricher,
                              ScriptSlicer slicer, VideoConcatenator concatenator,
                              AppProperties props, EnrichPrompt enrichPrompt) {
        this.generator = generator;
        this.enricher = enricher;
        this.slicer = slicer;
        this.concatenator = concatenator;
        this.props = props;
        this.enrichPrompt = enrichPrompt;
    }

    /* ================ 接口 ================ */

    @PostMapping("/api/generate")
    public Map<String, Object> generate(@RequestBody Map<String, String> body) {
        String topic = body.getOrDefault("topic", "").trim();
        String persona = body.getOrDefault("persona", "anuo").trim().toLowerCase();
        if (topic.isEmpty()) {
            return Map.of("error", "topic 不能为空");
        }
        if (!"pan".equals(persona) && !"anuo".equals(persona)) {
            persona = "anuo";
        }
        final String p = persona;
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        tasks.put(taskId, TaskInfo.running());
        executor.submit(() -> runPipeline(taskId, topic, p));
        return Map.of("taskId", taskId);
    }

    @GetMapping("/api/task/{id}")
    public Map<String, Object> task(@org.springframework.web.bind.annotation.PathVariable String id) {
        TaskInfo t = tasks.get(id);
        if (t == null) return Map.of("status", "not_found");
        if (t.error != null) return Map.of("status", t.status, "error", t.error);
        if (t.videoUrl != null) return Map.of("status", t.status, "videoUrl", t.videoUrl);
        return Map.of("status", t.status);
    }

    /* ================ 管线（与 DemoApplication 同流程，按任务隔离目录） ================ */

    private void runPipeline(String taskId, String topic, String persona) {
        TaskInfo t = tasks.get(taskId);
        try {
            String personaName = "anuo".equals(persona) ? "阿诺" : "老潘";
            String docsDir = props.getDocsDir() + "/" + persona;
            System.out.println("[" + taskId + "] 开始出片：persona=" + persona + " topic=" + topic);

            // Stage1：RAG 写稿
            generator.buildKnowledgeBase(docsDir);
            String script = generator.generate(topic, persona);
            System.out.println("[" + taskId + "] [Stage1] 口播稿：\n" + script);

            // Stage1.5：切片
            List<String> slices = slicer.slice(script);
            System.out.println("[" + taskId + "] [Stage1.5] 切片 " + slices.size() + " 段");

            // 每任务独立输出目录
            Path taskDir = Paths.get(props.getVideo().getOutputDir(), taskId);
            Files.createDirectories(taskDir);

            VideoGenerator videoGen = newVideoGenerator(persona);

            // Stage2+3：逐段富化 → 出片
            List<String> segPaths = new ArrayList<>();
            String prevTail = "";
            int failed = 0;
            for (int i = 0; i < slices.size(); i++) {
                String seg = slices.get(i);
                String segContext = buildSegmentContext(i, slices.size(), seg, prevTail);
                String segEnriched = enricher.enrich(segContext, personaName, persona);
                String segVisual = enrichPrompt.extractVisual(segEnriched);
                String segPrompt = segVisual + "\n\n[台词]\n" + seg;

                Path segFile = taskDir.resolve(String.format("seg_%02d.mp4", i + 1));
                String segMp4;
                if (Files.exists(segFile)) {
                    System.out.println("[" + taskId + "] [续跑] 片段 " + (i + 1) + " 已存在，跳过出片");
                    segMp4 = segFile.toAbsolutePath().toString();
                } else {
                    try {
                        segMp4 = videoGen.generate(segPrompt, i + 1, slices.size());
                    } catch (Exception e) {
                        failed++;
                        System.out.println("[" + taskId + "] [警告] 片段 " + (i + 1) + " 出片失败：" + e.getMessage());
                        prevTail = tailOf(seg);
                        continue;
                    }
                }
                segPaths.add(segMp4);
                prevTail = tailOf(seg);
            }

            // Stage4：拼接
            if (segPaths.isEmpty()) {
                t.fail("所有片段出片失败（共 " + slices.size() + " 段），详见后端日志");
                return;
            }
            String finalMp4 = segPaths.size() == 1
                    ? segPaths.get(0)
                    : concatenator.concat(segPaths);
            String fileName = Paths.get(finalMp4).getFileName().toString();
            t.done("/output/" + taskId + "/" + fileName);
            System.out.println("[" + taskId + "] 完成：" + finalMp4);

        } catch (Exception e) {
            t.fail(e.getMessage() == null ? e.toString() : e.getMessage());
            System.out.println("[" + taskId + "] 任务失败：" + e);
        }
    }

    /** 引擎工厂：与 DemoApplication 保持一致，但按任务的 persona 现建实例 */
    private VideoGenerator newVideoGenerator(String persona) {
        String provider = props.getVideo().getProvider();
        if ("grok".equalsIgnoreCase(provider)) return new GrokVideoGenService(props, persona);
        if ("volcengine".equalsIgnoreCase(provider)) return new VolcengineVideoGenService(props, persona);
        return new VideoGenService(props, persona);
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

    /* ================ 任务状态 ================ */

    static final class TaskInfo {
        volatile String status;
        volatile String videoUrl;
        volatile String error;

        private TaskInfo(String status) { this.status = status; }
        static TaskInfo running() { return new TaskInfo("running"); }
        void done(String url) { this.status = "done"; this.videoUrl = url; }
        void fail(String msg) { this.status = "failed"; this.error = msg; }
    }
}
