package com.view163.digitalhuman.web;

import com.view163.digitalhuman.config.AppProperties;
import com.view163.digitalhuman.prompt.EnrichPrompt;
import com.view163.digitalhuman.service.GrokVideoGenService;
import com.view163.digitalhuman.service.H3VideoGenService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            s = new WizardSession(normalizePersona(str(body.get("persona"))));
            sessions.put(taskId, s);
            created = true;
        }
        if (script.isEmpty()) script = s.script;
        // ★ 配音驱动（2026-10-21 老板口径：先有配音后出片）：persona 配了参考音频时，
        //   切片目标直接按音频实际时长算，不看前端下拉——否则按 30s 切的段塞进 10s 的片子，台词必然溢出。
        //   ★ 2026-10-21 修正：配音是「按台词分句」的有序序列（第一句/第二句/第三句各一条），
        //     所以段数 = 配音条数、第 i 段字数按第 i 句秒数占比分（sliceByPlan），
        //     这样出片时第 N 段正好挂第 N 条音频。
        List<Integer> plan = new ArrayList<>();
        for (String f : props.getVideo().getH3().getReferenceAudiosFor(s.persona)) {
            Integer sec = AppProperties.probeAudioSeconds(f, props.getVideo().getFfmpegPath());
            if (sec != null && sec > 0) plan.add(sec);
        }
        Integer audioSecs = null;
        try {
            audioSecs = props.getVideo().totalAudioSeconds(s.persona);
        } catch (Exception ignore) { }
        boolean audioDriven = audioSecs != null && audioSecs > 0;
        int seconds;
        if (audioDriven) {
            seconds = audioSecs;
        } else {
            seconds = intVal(body.get("seconds"), s.seconds);
            seconds = Math.max(5, Math.min(30, seconds));   // 前端承诺 5-30，后端再夹一次
        }

        List<String> segments = (plan.size() > 1)
                ? slicer.sliceByPlan(script, plan)
                : slicer.slice(script, seconds);
        s.script = script;
        s.seconds = seconds;
        s.segments = segments;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("seconds", seconds);
        result.put("audioDriven", audioDriven);
        result.put("audioSentences", plan.size());
        result.put("perSentenceSeconds", plan);
        result.put("segments", segmentList(segments));
        return result;
    }

    /* ────────────── 第2.5步：手动编辑切片（增/删/改/调序后整表回写） ────────────── */

    /**
     * POST /api/wizard/segments  {taskId, script, persona, segments:["第一段文案", "第二段文案", ...]}
     *   → {taskId, segments:[{index,text,chars}]}
     *
     * 前端在“手动添加片段 / 删除片段 / 上移下移 / 编辑文案”后整表回写，
     * 让服务端 s.segments 与页面顺序保持一致（富化的“上一段结尾”“共 N 段”依赖它）。
     * 会话不存在时用 script 兜底建会话（纯手动录入文案的场景）。
     * 空字符串允许存在（用户刚加的片段还没填字），但整表不能为空。
     */
    @PostMapping("/api/wizard/segments")
    public Map<String, Object> updateSegments(@RequestBody Map<String, Object> body) {
        String taskId = str(body.get("taskId"));
        String script = str(body.get("script"));
        WizardSession s = sessions.get(taskId);
        if (s == null) {
            if (script.isEmpty()) return Map.of("error", "会话不存在或已过期，请先在「文案」步骤生成/粘贴口播稿");
            taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            s = new WizardSession(normalizePersona(str(body.get("persona"))));
            sessions.put(taskId, s);
        }

        List<String> segments = new ArrayList<>();
        Object raw = body.get("segments");
        if (raw instanceof List<?> arr) {
            for (Object o : arr) segments.add(o == null ? "" : o.toString().trim());
        }
        if (segments.isEmpty()) return Map.of("error", "至少保留一段切片");

        s.script = script.isEmpty() ? s.script : script;
        s.segments = segments;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("segments", segmentList(segments));
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
            String personaName = "anuo".equals(s.persona) ? "阿诺" : "qipao".equals(s.persona) ? "旗袍主播" : "老潘";
            String prevTail = (index > 1 && s.segments != null && index - 2 < s.segments.size())
                    ? tailOf(s.segments.get(index - 2)) : "";
            String segContext = buildSegmentContext(index - 1, totalSegments(s), segmentText, prevTail);
            String enriched = enricher.enrich(segContext, personaName, s.persona, index);
            String visual = enrichPrompt.extractVisual(enriched, s.persona, index);
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
            // ★ 去重：EnrichPrompt 模板可能已让 LLM 生成过 [台词] 段，先剥掉，
            //   再用真实 segmentText 追加唯一一次，避免成片里 [台词] 出现两遍。
            String cleanVisual = visualPrompt;
            int tIdx = cleanVisual.lastIndexOf("[台词]");
            if (tIdx >= 0) cleanVisual = cleanVisual.substring(0, tIdx).trim();
            String prompt = cleanVisual + "\n\n[台词]\n" + segmentText;
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
        if ("h3".equalsIgnoreCase(provider)) return new H3VideoGenService(props, persona, seconds, outputDir);
        return new VideoGenService(props, persona, seconds, outputDir);
    }

    /* ────────────── 配音（时长基准）：查看 / 上传 / 恢复 ────────────── */

    /** GET /api/wizard/audio?persona=anuo → 当前生效的配音（工作台上传的优先，否则配置文件里的） */
    @GetMapping("/api/wizard/audio")
    public Map<String, Object> audioInfo(@RequestParam(value = "persona", defaultValue = "anuo") String persona) {
        return audioInfoMap(normalizePersona(persona));
    }

    /**
     * POST /api/wizard/audio（multipart：file + persona + activate）
     * 上传一句台词配音：存到 audio/&lt;persona&gt;/&lt;原文件名&gt;。
     * activate=true（默认）→ **追加到台词序列末尾**（第 1 条 = 第一句，第 2 条 = 第二句…）；
     * activate=false → 只入库，不排进序列（之后在页面上手动排）。
     * ★ 2026-10-21 修正：不再是「一条覆盖整片」，而是按句追加。
     */
    @PostMapping("/api/wizard/audio")
    public Map<String, Object> uploadAudio(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "persona", defaultValue = "anuo") String persona,
                                           @RequestParam(value = "activate", defaultValue = "true") boolean activate) {
        String p = normalizePersona(persona);
        if (file == null || file.isEmpty()) return Map.of("error", "文件为空");
        String raw = file.getOriginalFilename();
        String name = (raw == null || raw.isBlank()) ? "audio.wav"
                : Paths.get(raw).getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!Set.of("wav", "mp3", "m4a", "flac", "aac", "ogg").contains(ext)) {
            return Map.of("error", "只支持 wav / mp3 / m4a / flac / aac / ogg，收到：" + ext);
        }
        if (file.getSize() > 60L * 1024 * 1024) return Map.of("error", "文件超过 60MB");
        try {
            Path dir = Paths.get("audio", p);
            Files.createDirectories(dir);
            Path dst = dir.resolve(name).toAbsolutePath();
            file.transferTo(dst);
            int position = 0;
            if (activate) {
                // 序列还是空的（没手动排过）→ 以当前生效列表为基线，避免把配置里的句子顶掉
                List<String> seq = new ArrayList<>(AppProperties.getAudioSequence(p));
                if (seq.isEmpty()) seq = new ArrayList<>(props.getVideo().getH3().getConfiguredAudiosFor(p));
                seq.add(dst.toString());
                AppProperties.setAudioSequence(p, seq);
                position = AppProperties.getAudioSequence(p).indexOf(dst.toString()) + 1;
            }
            Map<String, Object> info = new LinkedHashMap<>(audioInfoMap(p));
            info.put("ok", true);
            info.put("uploaded", name);
            info.put("position", position);
            info.put("uploadedSeconds", AppProperties.probeAudioSeconds(dst.toString(), props.getVideo().getFfmpegPath()));
            info.put("activated", activate);
            info.put("note", activate
                    ? ("已作为第 " + position + " 句台词排进配音序列；出片时第 " + position + " 段就用它")
                    : "已加入素材库，还没排进台词序列");
            System.out.println("[Wizard] 配音已上传" + (activate ? ("并排为第 " + position + " 句") : "入库") + "：" + p
                    + " → " + dst + "（" + info.get("uploadedSeconds") + "s）");
            return info;
        } catch (Exception e) {
            return Map.of("error", "上传失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * POST /api/wizard/audio/sequence {persona, paths:[...]}
     * 整体设置台词序列（前端上移/下移/移除后回写）。paths 的顺序就是台词顺序。
     * 只允许配置里的 + 本 persona 上传目录下的音频。
     */
    @PostMapping("/api/wizard/audio/sequence")
    public Map<String, Object> setAudioSequence(@RequestBody Map<String, Object> body) {
        String p = normalizePersona(str(body.get("persona")));
        List<String> paths = new ArrayList<>();
        Object raw = body.get("paths");
        if (raw instanceof List<?> arr) {
            for (Object o : arr) if (o != null && !o.toString().isBlank()) paths.add(o.toString().trim());
        }
        for (String path : paths) {
            Path t = Paths.get(path).toAbsolutePath().normalize();
            boolean allowed = t.startsWith(Paths.get("audio", p).toAbsolutePath().normalize());
            if (!allowed) {
                for (String c : props.getVideo().getH3().getConfiguredAudiosFor(p)) {
                    if (c != null && Paths.get(c.trim()).toAbsolutePath().normalize().equals(t)) { allowed = true; break; }
                }
            }
            if (!allowed) return Map.of("error", "只能排配置里的或本页上传的配音：" + path);
        }
        AppProperties.setAudioSequence(p, paths);
        Map<String, Object> r = new LinkedHashMap<>(audioInfoMap(p));
        r.put("ok", true);
        System.out.println("[Wizard] 台词序列已更新：" + p + " → " + paths.size() + " 句 " + paths);
        return r;
    }

    /** POST /api/wizard/audio/clear {persona} → 清空运行时台词序列，回落到配置文件里的 */
    @PostMapping("/api/wizard/audio/clear")
    public Map<String, Object> clearAudio(@RequestBody Map<String, Object> body) {
        String p = normalizePersona(str(body.get("persona")));
        AppProperties.setAudioSequence(p, List.of());
        AppProperties.clearAudioOverride(p);
        System.out.println("[Wizard] 台词序列已清空：" + p + "（回落到配置）");
        return audioInfoMap(p);
    }

    /**
     * 当前配音序列详情。
     * 兼容老字段（name/seconds/path/source = 第一句），另加 items/count/totalSeconds 描述整个台词序列。
     * source 按路径判——upload（工作台上传的）/ config（配置文件）/ none（没有）。
     */
    private Map<String, Object> audioInfoMap(String persona) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("persona", persona);
        List<String> seq = props.getVideo().getH3().getReferenceAudiosFor(persona);
        Path uploadRoot = Paths.get("audio").toAbsolutePath().normalize();

        List<Map<String, Object>> items = new ArrayList<>();
        int total = 0;
        boolean allOk = true;
        for (String f : seq) {
            if (f == null || f.isBlank()) continue;
            Path ap = Paths.get(f.trim()).toAbsolutePath().normalize();
            Integer sec = AppProperties.probeAudioSeconds(ap.toString(), props.getVideo().getFfmpegPath());
            if (sec == null || sec <= 0) allOk = false; else total += sec;
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("name", ap.getFileName().toString());
            it.put("path", ap.toString());
            it.put("seconds", sec);
            it.put("source", ap.startsWith(uploadRoot) ? "upload" : "config");
            try { it.put("sizeMb", String.format("%.1f", Files.size(ap) / 1048576.0)); } catch (Exception ignore) { }
            items.add(it);
        }
        m.put("items", items);
        m.put("count", items.size());
        m.put("totalSeconds", (allOk && total > 0) ? total : null);
        m.put("sentences", items.size());

        if (items.isEmpty()) {
            m.put("source", "none");
            m.put("name", "");
            m.put("seconds", null);
            return m;
        }
        Map<String, Object> first = items.get(0);
        m.put("source", first.get("source"));
        m.put("name", items.size() == 1 ? first.get("name") : ("共 " + items.size() + " 句台词"));
        m.put("path", first.get("path"));
        m.put("seconds", m.get("totalSeconds"));
        return m;
    }

    /** GET /api/wizard/audio/list?persona= → 该人设的全部候选配音（配置里的 + 工作台上传的），标注在第几句 */
    @GetMapping("/api/wizard/audio/list")
    public Map<String, Object> audioList(@RequestParam(value = "persona", defaultValue = "anuo") String persona) {
        String p = normalizePersona(persona);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("persona", p);
        // 台词序列：运行时序列优先，否则配置（顺序即第 1 句/第 2 句…）
        List<String> seq = props.getVideo().getH3().getReferenceAudiosFor(p);
        List<String> seqAbs = new ArrayList<>();
        for (String s : seq) if (s != null && !s.isBlank()) seqAbs.add(Paths.get(s.trim()).toAbsolutePath().normalize().toString());

        List<Map<String, Object>> tracks = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String c : props.getVideo().getH3().getConfiguredAudiosFor(p)) {
            if (c == null || c.isBlank()) continue;
            String abs = Paths.get(c.trim()).toAbsolutePath().normalize().toString();
            if (seen.add(abs)) tracks.add(trackMap(abs, "config", seqAbs.indexOf(abs) + 1));
        }
        Path dir = Paths.get("audio", p);
        if (Files.isDirectory(dir)) {
            try (var st = Files.list(dir)) {
                st.filter(Files::isRegularFile).sorted().forEach(f -> {
                    String name = f.getFileName().toString();
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
                    if (!Set.of("wav", "mp3", "m4a", "flac", "aac", "ogg").contains(ext)) return;
                    String abs = f.toAbsolutePath().normalize().toString();
                    if (seen.add(abs)) tracks.add(trackMap(abs, "upload", seqAbs.indexOf(abs) + 1));
                });
            } catch (Exception ignore) { }
        }
        m.put("tracks", tracks);
        m.put("sequence", seqAbs);
        m.put("count", seqAbs.size());
        m.put("totalSeconds", props.getVideo().totalAudioSeconds(p));
        return m;
    }

    /** 一条候选配音的详情（name/path/source/seconds/sizeMb/seq，seq = 第几句，0 = 未入序列） */
    private Map<String, Object> trackMap(String absPath, String source, int seq) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", Paths.get(absPath).getFileName().toString());
        t.put("path", absPath);
        t.put("source", source);
        t.put("seq", seq);
        t.put("active", seq > 0);
        t.put("seconds", AppProperties.probeAudioSeconds(absPath, props.getVideo().getFfmpegPath()));
        try {
            double mb = Files.size(Paths.get(absPath)) / 1048576.0;
            t.put("sizeMb", String.format("%.1f", mb));
        } catch (Exception ignore) { }
        return t;
    }

    /**
     * POST /api/wizard/audio/select {persona, path} → 把台词序列重置成「只有这一句」。
     * 单句口播（整片就一段台词）时用；多句场景请调 /api/wizard/audio/sequence 排顺序。
     */
    @PostMapping("/api/wizard/audio/select")
    public Map<String, Object> selectAudio(@RequestBody Map<String, Object> body) {
        String p = normalizePersona(str(body.get("persona")));
        String path = str(body.get("path"));
        if (path == null || path.isBlank()) return Map.of("error", "缺少 path");
        Path target = Paths.get(path.trim()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(target)) return Map.of("error", "文件不存在：" + path);
        boolean allowed = false;
        for (String c : props.getVideo().getH3().getConfiguredAudiosFor(p)) {
            if (c != null && Paths.get(c.trim()).toAbsolutePath().normalize().equals(target)) { allowed = true; break; }
        }
        if (!allowed && target.startsWith(Paths.get("audio", p).toAbsolutePath().normalize())) allowed = true;
        if (!allowed) return Map.of("error", "只能选配置里的或上传的配音");
        AppProperties.setAudioSequence(p, List.of(target.toString()));
        Map<String, Object> info = audioInfoMap(p);
        info.put("ok", true);
        System.out.println("[Wizard] 台词序列重置为单句：" + p + " → " + target);
        return info;
    }

    /** POST /api/wizard/audio/delete {persona, path} → 删除一条上传的配音（配置里的不让删），并从序列移除 */
    @PostMapping("/api/wizard/audio/delete")
    public Map<String, Object> deleteAudio(@RequestBody Map<String, Object> body) {
        String p = normalizePersona(str(body.get("persona")));
        String path = str(body.get("path"));
        if (path == null || path.isBlank()) return Map.of("error", "缺少 path");
        Path target = Paths.get(path.trim()).toAbsolutePath().normalize();
        if (!target.startsWith(Paths.get("audio", p).toAbsolutePath().normalize())) {
            return Map.of("error", "只能删除上传的配音，配置文件里的删不了");
        }
        try {
            AppProperties.removeFromSequence(p, target.toString());
            String active = AppProperties.getAudioOverride(p);
            if (active != null && Paths.get(active).toAbsolutePath().normalize().equals(target)) {
                AppProperties.clearAudioOverride(p);
            }
            Files.deleteIfExists(target);
            Map<String, Object> r = audioInfoMap(p);
            r.put("ok", true);
            System.out.println("[Wizard] 删除上传配音：" + target);
            return r;
        } catch (Exception e) {
            return Map.of("error", "删除失败：" + e.getMessage());
        }
    }

    /* ────────────── 工具 ────────────── */

    /** 切片列表 → 前端结构（index 从 1 开始，chars 用于估算秒数） */
    private static List<Map<String, Object>> segmentList(List<String> segments) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String text = segments.get(i) == null ? "" : segments.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i + 1);
            m.put("text", text);
            m.put("chars", text.length());
            list.add(m);
        }
        return list;
    }

    private static String normalizePersona(String p) {
        String v = p == null ? "" : p.trim().toLowerCase();
        if ("pan".equals(v) || "kaka".equals(v) || "samuel".equals(v) || "qipao".equals(v)) return v;
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
