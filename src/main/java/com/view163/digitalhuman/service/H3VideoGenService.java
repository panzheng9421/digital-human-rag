package com.view163.digitalhuman.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.view163.digitalhuman.config.AppProperties;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地 H3 出片引擎（MiniMax-H3 r2v，跑在本机 ComfyUI 上）。
 *
 * 与云端引擎的差别（决定了这里的写法）：
 *  1. 走 ComfyUI API：POST /prompt 提交 → GET /history/{id} 轮询 → GET /view 下载 MP4。
 *  2. 用 r2v（参考图生视频）pipeline：MiniMaxH3ReferenceToVideo + ref2va 权重 + Qwen3-VL 文本编码器，
 *     参考图决定"人物长相"，提示词决定"演什么"。参考图为空时该节点退化成纯文生视频（仍能跑，只是人物不锁定）。
 *  3. 单段有硬上限：帧数训练范围 124–362 帧 @24fps ≈ 5.2–15.1 秒。
 *     所以切片秒数 >15s 时，这里自动拆成若干 ≤15s 子段串行生成，再用 ffmpeg 拼成一段返回，
 *     对上层（向导/CLI）完全透明。
 *  4. 一张卡一次只能跑一个 H3（显存/内存都吃满），因此 generate 全程加锁串行，
 *     即使上层用 3 并发线程池点多段，到这里也会排队，不会 OOM。
 *
 * 实现 VideoGenerator 契约：generate(visualPrompt, segIndex, total) → 本地 MP4 绝对路径。
 */
public class H3VideoGenService implements VideoGenerator {

    /** 全局串行锁：H3 单卡一次只能跑一个，避免多段并发把显存/内存打爆 */
    private static final Object H3_LOCK = new Object();
    /** 参考图上传缓存：本地绝对路径 → ComfyUI input 下的文件名（避免每段重复上传） */
    private static final Map<String, String> UPLOAD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private final AppProperties props;
    private final String persona;
    private final int seconds;
    private final String outputDir;
    private final AppProperties.Video.H3 h3;

    /** 按 SOP profile 解析出的实际硬参数（数值照抄 submit_r2v.py 的 PROFILES，两档不许混用） */
    private final boolean testProfile;
    private final int width;
    private final int height;
    private final int steps;
    /** 本档实际生效的 LoRA 文件名（formal/test 各绑一个，绝不串档） */
    private final String lora;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /**
     * 参考图引用标签：富化提示词里可能出现三种写法（英文 "Image 1"、H3 原生 "<Picture 1>"、中文 "@图片1"），
     * 统一映射成 H3 本地节点认的 "<Picture N>"，否则参考图挂不上（模型不知道哪句在指参考帧）。
     */
    private static final Pattern IMAGE_TAG = Pattern.compile("Image\\s*(\\d+)|<Picture\\s*(\\d+)>|@?图片\\s*(\\d+)");

    /**
     * 音频参考标签：富化提示词里可能出现 "@音频1" / "音频1" / "Audio 1" 等写法，
     * 统一映射成 H3 本地节点认的 "&lt;Audio N&gt;"（与 ref_audios.ref_audio_N 一一对应）。
     * ⚠️ 负向后顾 (?&lt;![&lt;\w]) 防止把已经是 "&lt;Audio 1&gt;" 的文本替换成 "&lt;&lt;Audio 1&gt;"。
     */
    private static final Pattern AUDIO_TAG = Pattern.compile("@?音频\\s*(\\d+)|(?<![<\\w])Audio\\s*(\\d+)");

    public H3VideoGenService(AppProperties props, String persona) {
        this(props, persona, 0, null);
    }

    /** 带覆盖构造器（向导式出片用）：secondsOverride>0 覆盖时长，outputDirOverride 非空覆盖输出目录 */
    public H3VideoGenService(AppProperties props, String persona, int secondsOverride, String outputDirOverride) {
        this.props = props;
        this.persona = persona;
        AppProperties.Video v = props.getVideo();
        this.h3 = v.getH3();
        this.seconds = secondsOverride > 0 ? secondsOverride : v.getSeconds();
        this.outputDir = (outputDirOverride != null && !outputDirOverride.isEmpty())
                ? outputDirOverride : v.getOutputDir();

        // ★ SOP 硬参数（MiniMaxH3_出片SOP.md「测试档/正式档铁律」，数值源 = submit_r2v.py PROFILES）：
        //   正式档 = 1344×768(0.98MP) + 8步 + 挂 ref2v 8step LoRA
        //   测试档 = 864×480(0.4MP)  + 4步 + 挂 ref2v 4step LoRA
        // ⚠️ LoRA 与步数必须同名配对：文件名里的数字就是它的蒸馏设计步数（NFE）。
        String p = h3.getProfile() == null ? "formal" : h3.getProfile().trim().toLowerCase();
        if ("test".equals(p)) {
            this.testProfile = true;
            this.width = h3.getTestWidth();
            this.height = h3.getTestHeight();
            this.steps = h3.getTestSteps();
            this.lora = h3.getTestLora();
        } else if ("custom".equals(p)) {
            this.testProfile = false;
            this.width = h3.getWidth();
            this.height = h3.getHeight();
            this.steps = h3.getSteps();
            this.lora = h3.isUseLora() ? h3.getLora() : "";
        } else {   // formal（也兼容历史写法 final）
            this.testProfile = false;
            this.width = h3.getFormalWidth();
            this.height = h3.getFormalHeight();
            this.steps = h3.getFormalSteps();
            this.lora = h3.getFormalLora();
        }
        // 防御：LoRA 名里的步数与设计 BF 不符时直接拦下（错配会「打折丢分镜」或「零增益纯变慢」）
        checkLoraStepPairing();
    }

    /**
     * ★ SOP 红线：LoRA 文件名里的数字 = 蒸馏设计步数，必须与实际 steps 一致。
     * 8step 的 LoRA 只跑 8 步、4step 的 LoRA 只跑 4 步，禁止错配。
     */
    private void checkLoraStepPairing() {
        if (lora == null || lora.isBlank()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)step").matcher(lora);
        if (!m.find()) return;
        int designed = Integer.parseInt(m.group(1));
        if (designed != steps) {
            throw new IllegalStateException("LoRA 与步数错配（SOP 红线）：" + lora + " 是 " + designed
                    + "step 蒸馏，却被配成 " + steps + " 步。"
                    + "8step 的 LoRA 只跑 8 步、4step 的 LoRA 只跑 4 步，请修 app.video.h3.profile 的档位参数。");
        }
    }

    @Override
    public String generate(String visualPrompt, int segIndex, int total) throws Exception {
        synchronized (H3_LOCK) {
            Path outDir = Paths.get(outputDir);
            Files.createDirectories(outDir);
            String baseName = segIndex > 0
                    ? String.format("seg_%02d.mp4", segIndex)
                    : ("output_" + UUID.randomUUID().toString().substring(0, 8) + ".mp4");
            Path target = outDir.resolve(baseName);

            checkComfyAlive();
            // ★ 配音驱动时长（2026-10-21 老板口径：先有配音、后让 H3 出完整视频）：
            //   persona 配了参考音频 → 本镜时长按音频实际长度算，配置秒数/前端传的秒数都不锁死它。
            //   多条音频按段序一一对应（第 N 段用第 N 条），越界回退第 1 条。
            Integer audioSecs = null;
            try {
                audioSecs = props.getVideo().audioSecondsFor(persona, segIndex);
            } catch (Exception ignore) { }
            int effSeconds = (audioSecs != null && audioSecs > 0) ? audioSecs : seconds;
            if (audioSecs != null && audioSecs > 0) {
                System.out.println("[H3] 配音驱动：本镜按配音实际时长 " + effSeconds + "s 生成"
                        + "（配置秒数 " + seconds + "s 不生效；音频短于 5.2s 时按训练下限 124 帧兜底）");
            }
            logLockedParams(segIndex, total, effSeconds);

            int wantFrames = secToFrames(effSeconds);
            int maxFrames = h3.getMaxFrames();
            int parts = Math.max(1, (int) Math.ceil((double) wantFrames / maxFrames));
            if (parts > 1) {
                System.out.println("[H3] 片段 " + segIndex + " 需要 " + effSeconds + "s，超出单段上限 "
                        + (maxFrames / 24) + "s，自动拆成 " + parts + " 个子段串行生成后拼接");
            }

            // ★ 先注入场景锁定再拆台词：顺序反了会被下面的 substring 覆盖掉（踩过一次）
            String lockedPrompt = applySceneLock(visualPrompt);
            // 视觉提示词 与 台词 分离：拆段时按句均分台词，避免同一段台词被念两遍
            String visual = lockedPrompt;
            String script = "";
            int cut = lockedPrompt.lastIndexOf("\n\n[台词]\n");
            if (cut >= 0) {
                visual = lockedPrompt.substring(0, cut);
                script = lockedPrompt.substring(cut + "\n\n[台词]\n".length()).trim();
            }
            List<String> scriptParts = splitScript(script, parts);

            List<Path> made = new ArrayList<>();
            for (int i = 0; i < parts; i++) {
                int frames = Math.min(maxFrames, wantFrames - i * maxFrames);
                frames = gridFrames(frames);
                String partPrompt = visual;
                if (!scriptParts.get(i).isEmpty()) {
                    partPrompt += "\n\n[台词]\n" + scriptParts.get(i);
                }
                Path p = runOne(partPrompt, frames, segIndex, i, parts);
                made.add(p);
                System.out.println("[H3] 片段 " + segIndex + " 子段 " + (i + 1) + "/" + parts
                        + " 完成（" + frames + " 帧 / " + String.format("%.1f", frames / 24.0) + "s）");
            }

            if (made.size() == 1) {
                Files.move(made.get(0), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else {
                concatTo(made, target);
            }
            System.out.println("[H3] 片段 " + segIndex + " r2v 出片完成：" + target.toAbsolutePath());

            // ② 超分（SOP 唯一标准：submit_vsr.py / nvvfx 硬件 VSR → 2x = 2688×1536）。
            //    测试档画质不作数、也不交付，跳过。
            if (!testProfile && "vsr".equalsIgnoreCase(h3.getUpscale())) {
                upscaleVsr(target, segIndex);
            }
            // ③ 换轨（★ SOP 默认不换）：H3 原生吃音频，直出音轨即交付音轨。
            //    仅当实测出现音画漂移、且配了 --swap-audio 时才用原 wav 覆盖。
            if (h3.isSwapAudio()) {
                replaceAudioTrack(target);
            } else {
                System.out.println("[H3] 音轨：H3 直出即交付（swap-audio=false，SOP 默认）");
            }

            System.out.println("[H3] 片段 " + segIndex + " 全链路完成：" + target.toAbsolutePath());
            return target.toAbsolutePath().toString();
        }
    }

    /* ────────── 单子段：上传参考图 → 提交 → 轮询 → 下载 ────────── */

    private Path runOne(String prompt, int frames, int segIndex, int partIdx, int parts) throws Exception {
        List<String> refNames = uploadReferenceImages();

        ObjectNode wf = buildWorkflow(prompt, frames, refNames, segIndex, partIdx);
        String pid = submit(wf);
        System.out.println("[H3] 已提交 pid=" + pid + "（片段 " + segIndex + " 子段 " + (partIdx + 1) + "/" + parts + "）");
        JsonNode hist = waitHistory(pid);
        return downloadResult(hist, pid, segIndex, partIdx);
    }

    /** 生成前锁定（出片校验 SOP 阶段一）：把本镜实际生效的硬参数打出来，便于提交前核对 */
    private void logLockedParams(int segIndex, int total, int effSeconds) {
        List<String> refs = h3.getReferenceImagesFor(persona);
        List<String> auds = h3.getReferenceAudiosFor(persona);
        System.out.println("[H3] ── 生成前锁定（出片校验 SOP 阶段一）──────────");
        System.out.println("[H3] 档位       ：" + (testProfile
                ? "测试档（0.4MP 864×480 / 4 步 / 挂 ref2v 4step LoRA；只看构图和节奏，画质不作数、不得交付）"
                : "正式档（0.98MP 1344×768 / 8 步 / 挂 ref2v 8step LoRA / 画质优先）"));
        System.out.println("[H3] 分辨率     ：" + width + "×" + height + "   步数：" + steps
                + "   采样：" + h3.getSampler() + "/" + h3.getScheduler() + " denoise=" + h3.getDenoise());
        System.out.println("[H3] ref2v LoRA ：" + (lora == null || lora.isBlank() ? "不挂" : lora
                + " (strength " + h3.getLoraStrength() + ")")
                + "   ← ★必须与 " + steps + " 步同名配对");
        System.out.println("[H3] 本镜时长   ：" + effSeconds + "s → " + secToFrames(effSeconds) + " 帧（17k+5 网格 @"
                + h3.getFps() + "fps；H3 训练区间 124–362 帧）");
        System.out.println("[H3] 参考图     ：" + (refs == null || refs.isEmpty()
                ? "❌ 未配置 → 本镜为纯文生视频，人物长相不锁定（资产未补齐前不得声称可直接出片）"
                : refs.size() + " 张：" + refs) + "   ref_image_size=" + h3.getRefImageSize());
        System.out.println("[H3] 参考音频   ：" + (auds == null || auds.isEmpty()
                ? "无 → 走 <d> 台词由 H3 发声（口型跟得上，音色是模型的）"
                : (auds.size() == 1
                    ? "1 条：" + auds.get(0) + "  → 接 ref_audios + <Audio 1>"
                    : "共 " + auds.size() + " 句台词，本镜用第 " + Math.min(Math.max(segIndex, 1), auds.size())
                      + " 句 → " + Paths.get(auds.get(Math.min(Math.max(segIndex, 1), auds.size()) - 1)).getFileName()
                      + "  （全部：" + auds + "）")));
        System.out.println("[H3] 超分       ：" + (testProfile ? "跳过（测试档）"
                : ("vsr".equalsIgnoreCase(h3.getUpscale()) ? "submit_vsr.py → 2x = 2688×1536" : h3.getUpscale()))
                + "   换轨：" + (h3.isSwapAudio() ? "开（漂移时才用；原音 " + h3.getAudioTrack() + "）" : "关（SOP 默认：H3 直出即交付）"));
        System.out.println("[H3] 片段序号   ：" + segIndex + " / 共 " + total);
        System.out.println("[H3] ────────────────────────────────────────");
    }

    /**
     * 超分：SOP 唯一标准 = submit_vsr.py（ComfyUI 现成节点 RTXVideoSuperResolution，nvvfx 硬件 VSR）。
     * 脚本硬编码把成片落到自己的 output_sr 目录，所以跑完要拷回项目目录并清掉那份，避免污染别的线。
     */
    private void upscaleVsr(Path mp4, int segIndex) {
        String py = (h3.getPythonPath() == null || h3.getPythonPath().isBlank()) ? "python" : h3.getPythonPath();
        String prefix = "dhr_sr_" + (segIndex > 0 ? String.format("%02d", segIndex) : "out");
        try {
            Path inDir = Paths.get(h3.getComfyInputDir());
            Files.createDirectories(inDir);
            Path src = inDir.resolve(prefix + ".mp4");
            Files.copy(mp4, src, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            System.out.println("[H3] 超分中（submit_vsr.py → nvvfx VSR），源：" + src.getFileName());
            Process p = new ProcessBuilder(py, h3.getVsrScript(), "--src", src.getFileName().toString(), "--prefix", prefix)
                    .redirectErrorStream(true).start();
            StringBuilder log = new StringBuilder();
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.append(line).append("\n");
                    if (line.startsWith("超分成片") || line.contains("ERROR") || line.contains("TIMEOUT")) {
                        System.out.println("[H3][vsr] " + line);
                    }
                }
            }
            int code = p.waitFor();
            if (code != 0) {
                System.out.println("[H3] 超分失败（退出码 " + code + "），保留 r2v 原片：" + mp4.getFileName());
                Files.deleteIfExists(src);
                return;
            }
            Path produced = Paths.get(h3.getVsrOutDir()).resolve(prefix + ".mp4");
            if (!Files.exists(produced)) {
                System.out.println("[H3] 超分输出未找到：" + produced + "，保留 r2v 原片");
                Files.deleteIfExists(src);
                return;
            }
            Files.copy(produced, mp4, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(produced);
            Files.deleteIfExists(src);
            System.out.println("[H3] 超分完成 → " + mp4.toAbsolutePath() + "（" + (Files.size(mp4) / 1024 / 1024) + "MB）");
        } catch (Exception e) {
            System.out.println("[H3] 超分异常，保留 r2v 原片：" + e.getMessage());
        }
    }

    /** 换回原音轨（H3 默认输出的是模型仿的音）。放在超分之后，画面零重编码 */
    private void replaceAudioTrack(Path mp4) {
        String audio = h3.getAudioTrack();
        if (audio == null || audio.isBlank() || !Files.exists(Paths.get(audio))) return;
        String ffmpeg = props.getVideo().getFfmpegPath();
        if (ffmpeg == null || ffmpeg.isBlank()) ffmpeg = "ffmpeg";
        try {
            Path tmp = mp4.resolveSibling(mp4.getFileName().toString().replace(".mp4", "_audio.mp4"));
            Process p = new ProcessBuilder(ffmpeg, "-i", mp4.toAbsolutePath().toString(),
                    "-i", audio, "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-shortest",
                    "-y", tmp.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            int code = p.waitFor();
            if (code == 0 && Files.exists(tmp) && Files.size(tmp) > 0) {
                Files.move(tmp, mp4, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[H3] 已换回原音轨：" + audio);
            } else {
                System.out.println("[H3] 换轨失败（退出码 " + code + "），保留原音轨");
                Files.deleteIfExists(tmp);
            }
        } catch (Exception e) {
            System.out.println("[H3] 换轨异常，保留原音轨：" + e.getMessage());
        }
    }

    /** 探测 ComfyUI 是否活着，早点报错比等到提交失败好定位 */
    private void checkComfyAlive() throws Exception {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(h3.getBaseUrl() + "/system_stats"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException("本地 ComfyUI 不可达（" + h3.getBaseUrl() + "）：" + e.getMessage()
                    + "。请先启动 ComfyUI（例如：python main.py --listen 127.0.0.1 --port 8198），"
                    + "并确认 app.video.h3.base-url 配置正确。");
        }
    }

    /** 把本地参考图上传到 ComfyUI input 目录，返回可用于 LoadImage 的文件名 */
    /**
     * ★ 场景锁定：把配置里写死的「背景/机位/光线」原样注入 prompt，绕开 LLM。
     *
     * 为什么需要：富化是「逐镜独立调 LLM」的，模板里那句「完整复刻图中场景」没有任何实体锚点
     * （阿诺的参考图是纯色棚拍，图里根本没有房间），模型只能自由发挥 → 每镜背景都不同。
     * 这里在提交前的最后一道关强制注入，所以无论 prompt 从哪来（富化输出 / 前端手改）都跑不掉。
     *
     * 幂等：prompt 里已经有 [场景锁定] 段就跳过，不重复插。
     */
    private String applySceneLock(String visual) {
        String lock = h3.getSceneLockFor(persona);
        if (lock == null || lock.isBlank()) return visual;
        String block = "[场景锁定]\n" + lock.trim() + "\n";
        if (visual.contains("[场景锁定]")) {
            System.out.println("[H3] 场景锁定：prompt 已含 [场景锁定] 段，跳过注入（幂等）");
            return visual;
        }
        // 插点优先级：整体风格 → 严格排除 → 事件脚本 之前；都没有就接在末尾
        String out = insertBefore(visual, "[整体风格]", block);
        if (out == null) out = insertBefore(visual, "[严格排除]", block);
        if (out == null) out = insertBefore(visual, "[事件脚本]", block);
        if (out == null) out = visual.trim() + "\n\n" + block;
        System.out.println("[H3] 场景锁定已注入（" + lock.trim().length() + " 字符）：\n" + block);
        return out;
    }

    private static String insertBefore(String text, String anchor, String block) {
        int at = text.indexOf(anchor);
        if (at < 0) return null;
        String head = text.substring(0, at).replaceAll("\\s+$", "");
        return head + "\n\n" + block + "\n" + text.substring(at);
    }

    private List<String> uploadReferenceImages() {
        List<String> files = h3.getReferenceImagesFor(persona);
        List<String> names = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            System.out.println("[H3] 未配置参考图（app.video.h3.reference-images），走纯文生视频：人物长相不锁定");
            return names;
        }
        for (int i = 0; i < files.size(); i++) {
            String f = files.get(i);
            if (f == null || f.isBlank()) continue;
            Path p = Paths.get(f.trim());
            if (!Files.exists(p)) {
                System.out.println("[H3] 参考图不存在，跳过：" + f);
                continue;
            }
            String cached = UPLOAD_CACHE.get(p.toAbsolutePath().toString());
            if (cached != null) { names.add(cached); continue; }
            try {
                String name = "dhr_ref_" + persona + "_" + i + extOf(p.getFileName().toString());
                byte[] body = multipartImage(p, name);
                HttpRequest req = HttpRequest.newBuilder(URI.create(h3.getBaseUrl() + "/upload/image"))
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    System.out.println("[H3] 参考图上传失败（" + resp.statusCode() + "）：" + resp.body());
                    continue;
                }
                JsonNode j = mapper.readTree(resp.body());
                String real = j.path("name").asText(name);
                UPLOAD_CACHE.put(p.toAbsolutePath().toString(), real);
                names.add(real);
                System.out.println("[H3] 参考图已上传：" + p.getFileName() + " → " + real);
            } catch (Exception e) {
                System.out.println("[H3] 参考图上传异常，跳过：" + f + " → " + e.getMessage());
            }
        }
        return names;
    }

    /**
     * 参考音频就位：拷进 ComfyUI input 目录，返回 LoadAudio 要用的文件名。
     * 与 submit_r2v.py 的做法一致（本地直拷，不走 HTTP 上传——ComfyUI 没有稳定的 /upload/audio 端点）。
     * LoadAudio 的 combo 会在提交时重新列举目录，复制后即可选中。
     *
     * ★ 2026-10-21 按台词分句（老板口径）：配音是一个**有序序列**（第一句、第二句、第三句…），
     *   第 segIndex 段镜头**只挂第 segIndex 条**音频——不能把全部音频都塞给每一段，
     *   否则第二段会听到第一句的声音，口型和台词全错。越界（段数 > 音频条数）回落第 1 条。
     */
    private List<String> stageReferenceAudios(int segIndex) {
        List<String> files = h3.getReferenceAudiosFor(persona);
        List<String> names = new ArrayList<>();
        if (files == null || files.isEmpty()) return names;
        int pickIdx = (segIndex >= 1 && segIndex <= files.size()) ? segIndex - 1 : 0;
        String f = files.get(pickIdx);
        if (f == null || f.isBlank()) return names;
        if (files.size() > 1) {
            System.out.println("[H3] 本镜配音：第 " + (pickIdx + 1) + "/" + files.size() + " 句 → "
                    + Paths.get(f).getFileName() + "（每段只用本句音频，不混入其它句）");
        }
        Path p = Paths.get(f.trim());
        if (!Files.exists(p)) {
            System.out.println("[H3] 参考音频不存在，跳过：" + f);
            return names;
        }
        try {
            Files.createDirectories(inDir(p));
            Path dst = inDir(p).resolve(p.getFileName().toString());
            if (!Files.exists(dst) || Files.size(dst) != Files.size(p)) {
                Files.copy(p, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[H3] 参考音频已就位：" + p.getFileName() + " → " + dst);
            }
            names.add(dst.getFileName().toString());
        } catch (Exception e) {
            System.out.println("[H3] 参考音频就位失败，跳过：" + f + " → " + e.getMessage());
        }
        return names;
    }

    /** ComfyUI input 目录（仅本段音频要拷进去，抽出来便于日志与异常处理） */
    private Path inDir(Path any) {
        return Paths.get(h3.getComfyInputDir());
    }

    private static final String BOUNDARY = "----dhrH3Upload" + UUID.randomUUID().toString().replace("-", "");

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        String e = i < 0 ? "" : name.substring(i).toLowerCase();
        return (e.equals(".jpg") || e.equals(".jpeg") || e.equals(".png") || e.equals(".webp")) ? e : ".png";
    }

    private static byte[] multipartImage(Path p, String name) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"" + name + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(Files.readAllBytes(p));
        bos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        bos.write(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"overwrite\"\r\n\r\ntrue\r\n").getBytes(StandardCharsets.UTF_8));
        bos.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return bos.toByteArray();
    }

    /**
     * 组装 H3 r2v 工作流（严格对齐官方 video_minimax_h3_r2v.json）：
     * UNETLoader(ref2va) →[可选 LoRA]→ BasicGuider/BasicScheduler，
     * KSamplerSelect=res_multistep、BasicScheduler=simple、RandomNoise → SamplerCustomAdvanced
     * → VAEDecode + VAEDecodeAudio → CreateVideo(24fps) → SaveVideo。
     * 注意：官方 r2v 模板没有 SigmaShift 节点；参考图标签本地节点用 <Picture N>。
     */
    private ObjectNode buildWorkflow(String prompt, int frames, List<String> refNames, int segIndex, int partIdx) {
        // ★ 先 staging 音频再修 prompt：fixPrompt 需要知道"有没有参考音频"来决定
        //   <Audio N> 是映射还是抹平（2026-10-21 教训：注入/映射类改动必须以实际报文复核）。
        List<String> audioNames = stageReferenceAudios(segIndex);
        String fixed = fixPrompt(prompt, refNames.isEmpty(), audioNames.isEmpty());
        Map<String, Object> nodes = new LinkedHashMap<>();

        nodes.put("1", node("UNETLoader", map("unet_name", h3.getUnet(), "weight_dtype", "default")));
        nodes.put("2", node("CLIPLoader", map("clip_name", h3.getClip(), "type", "minimax")));
        nodes.put("3", node("VAELoader", map("vae_name", h3.getVaeVideo())));
        nodes.put("4", node("VAELoader", map("vae_name", h3.getVaeAudio())));

        List<Object> chain = List.of("1", 0);
        if (lora != null && !lora.isBlank()) {
            Map<String, Object> loraNode = map("model", chain, "lora_name", lora,
                    "strength_model", h3.getLoraStrength());
            nodes.put("5", node("LoraLoaderModelOnly", loraNode));
            chain = List.of("5", 0);
        }

        // ★ 参考音频接线（SOP「音频驱动」）：LoadAudio 直接喂 r2v 的 ref_audios 端口，
        //   节点 id 与 submit_r2v.py 保持一致（100 起），prompt 里用 <Audio N> 引用。
        //   audio_vae（节点4）常态挂在 r2v 上——不接的话参考音频只条件化文本编码器，
        //   音色编码不进 latent（节点源码 tooltip 明示）。
        List<String> audioIds = new ArrayList<>();
        for (int i = 0; i < audioNames.size() && i < 3; i++) {   // 节点上限 ref_audio_1..3
            String aid = String.valueOf(100 + i);
            nodes.put(aid, node("LoadAudio", map("audio", audioNames.get(i))));
            audioIds.add(aid);
        }

        // 参考图 LoadImage（节点 id 从 200 起，与 submit_r2v.py 一致，避开主干 1-17）
        List<String> loadIds = new ArrayList<>();
        for (int i = 0; i < refNames.size() && i < 9; i++) {     // 节点上限 ref_image_1..9
            String lid = String.valueOf(200 + i);
            nodes.put(lid, node("LoadImage", map("image", refNames.get(i))));
            loadIds.add(lid);
        }

        Map<String, Object> r2v = new LinkedHashMap<>();
        r2v.put("clip", List.of("2", 0));
        r2v.put("vae", List.of("3", 0));
        r2v.put("audio_vae", List.of("4", 0));
        r2v.put("prompt", fixed);
        r2v.put("width", width);
        r2v.put("height", height);
        r2v.put("length", frames);
        r2v.put("ref_image_size", h3.getRefImageSize());
        for (int i = 0; i < loadIds.size(); i++) {
            r2v.put("ref_images.ref_image_" + (i + 1), List.of(loadIds.get(i), 0));
        }
        // ★ ref_audios 接 LoadAudio 输出（AUDIO 类型），与 prompt 里的 <Audio N> 一一对应
        for (int i = 0; i < audioIds.size(); i++) {
            r2v.put("ref_audios.ref_audio_" + (i + 1), List.of(audioIds.get(i), 0));
        }
        nodes.put("8", node("MiniMaxH3ReferenceToVideo", r2v));

        nodes.put("9", node("BasicGuider", map("model", chain, "conditioning", List.of("8", 0))));
        long seed = h3.getSeedBase() + (long) segIndex * 1000 + partIdx;
        nodes.put("10", node("RandomNoise", map("noise_seed", seed)));
        nodes.put("11", node("KSamplerSelect", map("sampler_name", h3.getSampler())));
        nodes.put("12", node("BasicScheduler", map(
                "model", chain, "scheduler", h3.getScheduler(), "steps", steps, "denoise", h3.getDenoise())));
        nodes.put("13", node("SamplerCustomAdvanced", map(
                "noise", List.of("10", 0), "guider", List.of("9", 0), "sampler", List.of("11", 0),
                "sigmas", List.of("12", 0), "latent_image", List.of("8", 1))));

        nodes.put("14", node("VAEDecode", map("samples", List.of("13", 0), "vae", List.of("3", 0))));
        nodes.put("15", node("VAEDecodeAudio", map("samples", List.of("13", 0), "vae", List.of("4", 0))));
        // ★ CreateVideo.fps 必须是浮点（SOP 写 24.0，submit_r2v.py 也是 24.0）
        nodes.put("16", node("CreateVideo", map("images", List.of("14", 0), "audio", List.of("15", 0),
                "fps", (double) h3.getFps())));
        String prefix = "dhr/" + (segIndex > 0 ? ("seg" + String.format("%02d", segIndex)) : "out") + "_p" + partIdx;
        // ★ SaveVideo.format 固定 "mp4"（与 submit_r2v.py 一致；用 auto 在部分版本会落到 webm）
        nodes.put("17", node("SaveVideo", map("video", List.of("16", 0), "filename_prefix", prefix, "format", "mp4")));

        ObjectNode wf = mapper.createObjectNode();
        wf.set("prompt", mapper.valueToTree(nodes));
        wf.put("client_id", "digital-human-rag");
        return wf;
    }

    /**
     * 提示词修正：
     *  - "Image N" → "&lt;Picture N&gt;"（云端节点写法改成本地 H3 节点的写法）
     *  - "@音频N" / "Audio N" → "&lt;Audio N&gt;"（配了参考音频才映射；没配则抹平引用，防止模型去找不存在的音频）
     *  - 没挂参考图时，把 <Picture N> / Image N 这类引用抹平，避免模型去找不存在的参考帧
     */
    private static String fixPrompt(String prompt, boolean noImageRef, boolean noAudioRef) {
        // 音频标签先行处理（图片标签的 IMAGE_TAG 不含音频，两条互不干扰）
        Matcher am = AUDIO_TAG.matcher(prompt);
        StringBuilder asb = new StringBuilder();
        while (am.find()) {
            if (noAudioRef) {
                am.appendReplacement(asb, "配音");
            } else {
                String n = am.group(1) != null ? am.group(1) : am.group(2);
                am.appendReplacement(asb, "<Audio " + n + ">");
            }
        }
        am.appendTail(asb);
        prompt = asb.toString();

        if (noImageRef) {
            return IMAGE_TAG.matcher(prompt).replaceAll("人物");
        }
        Matcher m = IMAGE_TAG.matcher(prompt);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
            m.appendReplacement(sb, "<Picture " + n + ">");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String submit(ObjectNode wf) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(h3.getBaseUrl() + "/prompt"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(wf.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("ComfyUI 提交失败 HTTP " + resp.statusCode() + "：" + clip(resp.body(), 600));
        }
        JsonNode j = mapper.readTree(resp.body());
        if (j.has("error")) {
            throw new IllegalStateException("ComfyUI 拒绝工作流：" + clip(j.get("error").toString(), 600)
                    + "（常见原因：权重文件名与本机不符，检查 app.video.h3.unet/clip/vae-video）");
        }
        return j.path("prompt_id").asText("");
    }

    private JsonNode waitHistory(String pid) throws Exception {
        long deadline = System.currentTimeMillis() + h3.getTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(h3.getBaseUrl() + "/history/" + pid))
                        .timeout(Duration.ofSeconds(30)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode j = mapper.readTree(resp.body());
                    if (j.has(pid)) return j.get(pid);
                }
            } catch (Exception ignore) {
                // 抖动，下轮再查
            }
            Thread.sleep(5000);
        }
        throw new IllegalStateException("H3 生成超时（" + h3.getTimeoutSeconds() + "s，pid=" + pid
                + "）。可在 ComfyUI 页面查看是否卡住/OOM。");
    }

    /** 从 SaveVideo 节点输出里取视频，经 /view 下载到 outputDir */
    private Path downloadResult(JsonNode hist, String pid, int segIndex, int partIdx) throws Exception {
        JsonNode outs = hist.path("outputs").path("17");
        JsonNode items = null;
        if (!outs.isMissingNode()) {
            items = outs.path("videos");
            if (items.size() == 0) items = outs.path("images");
            if (items.size() == 0) items = outs.path("gifs");
        }
        if (items == null || items.size() == 0) {
            String err = hist.path("status").path("error").toString();
            throw new IllegalStateException("H3 没有产出视频（pid=" + pid + "）。"
                    + (err.isEmpty() ? "" : " ComfyUI 报错：" + clip(err, 500)));
        }
        JsonNode v = items.get(0);
        String filename = v.path("filename").asText("");
        String subfolder = v.path("subfolder").asText("");
        String type = v.path("type").asText("output");

        String url = h3.getBaseUrl() + "/view?filename=" + enc(filename)
                + "&subfolder=" + enc(subfolder) + "&type=" + enc(type);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(300)).GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("下载 H3 视频失败 HTTP " + resp.statusCode() + "：" + url);
        }
        Path outDir = Paths.get(outputDir);
        Files.createDirectories(outDir);
        String name = (segIndex > 0 ? ("seg_" + String.format("%02d", segIndex)) : "out") + "_part" + partIdx + ".mp4";
        Path dst = outDir.resolve(name);
        Files.write(dst, resp.body());
        return dst;
    }

    /** 多子段 → ffmpeg 拼成一段（先 -c copy，失败自动重编码） */
    private void concatTo(List<Path> parts, Path target) throws Exception {
        String ffmpeg = props.getVideo().getFfmpegPath();
        if (ffmpeg == null || ffmpeg.isBlank()) ffmpeg = "ffmpeg";
        Path listFile = target.getParent().resolve("concat_" + target.getFileName() + ".txt");
        try (var w = Files.newBufferedWriter(listFile, StandardCharsets.UTF_8)) {
            for (Path p : parts) {
                w.write("file '" + p.toAbsolutePath().toString().replace("'", "'\\''") + "'");
                w.newLine();
            }
        }
        int code = runFfmpeg(ffmpeg, listFile, target, true);
        if (code != 0) {
            System.out.println("[H3] 拼接 stream copy 失败，改重编码…");
            code = runFfmpeg(ffmpeg, listFile, target, false);
        }
        Files.deleteIfExists(listFile);
        for (Path p : parts) Files.deleteIfExists(p);
        if (code != 0) throw new IllegalStateException("ffmpeg 拼接失败，退出码=" + code + "（检查 app.video.ffmpeg-path）");
    }

    private static int runFfmpeg(String ffmpeg, Path listFile, Path target, boolean copy) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-f"); cmd.add("concat");
        cmd.add("-safe"); cmd.add("0");
        cmd.add("-i"); cmd.add(listFile.toAbsolutePath().toString());
        if (copy) { cmd.add("-c"); cmd.add("copy"); }
        else { cmd.add("-c:v"); cmd.add("libx264"); cmd.add("-c:a"); cmd.add("aac"); }
        cmd.add("-y");
        cmd.add(target.toAbsolutePath().toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String low = line.toLowerCase();
                if (low.contains("error") || low.contains("fail")) System.out.println("[ffmpeg] " + line);
            }
        }
        return p.waitFor();
    }

    /* ────────── 工具 ────────── */

    /** 秒 → 帧数（@fps），并按 H3 训练下限夹一次 */
    private int secToFrames(int sec) {
        int f = (int) Math.round((double) sec * h3.getFps());
        return Math.max(h3.getMinFrames(), f);
    }

    /** 帧数对齐到 17k+5 网格（H3 length 的步长是 17，官方网格） */
    static int gridFrames(int frames) {
        int min = 124;
        int f = Math.max(min, frames);
        int k = 0;
        while (17 * k + 5 < f) k++;
        int g = 17 * k + 5;
        return Math.min(g, 362);
    }

    /** 台词按句均分成 n 份（拆段用，避免同一句台词被两个子段各念一遍） */
    static List<String> splitScript(String script, int n) {
        List<String> res = new ArrayList<>();
        if (n <= 1 || script.isEmpty()) {
            res.add(script);
            for (int i = 1; i < n; i++) res.add("");
            return res;
        }
        List<String> sents = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char c : script.toCharArray()) {
            cur.append(c);
            if ("。！？!?；;\n".indexOf(c) >= 0) { sents.add(cur.toString()); cur.setLength(0); }
        }
        if (cur.length() > 0) sents.add(cur.toString());
        if (sents.size() <= n) {
            for (int i = 0; i < n; i++) res.add(i < sents.size() ? sents.get(i) : "");
            return res;
        }
        int target = (int) Math.ceil((double) script.length() / n);
        StringBuilder bucket = new StringBuilder();
        for (String s : sents) {
            bucket.append(s);
            if (bucket.length() >= target && res.size() < n - 1) {
                res.add(bucket.toString().trim());
                bucket.setLength(0);
            }
        }
        res.add(bucket.toString().trim());
        while (res.size() < n) res.add("");
        return res;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> node(String classType, Map<String, Object> inputs) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("class_type", classType);
        n.put("inputs", inputs);
        return n;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
