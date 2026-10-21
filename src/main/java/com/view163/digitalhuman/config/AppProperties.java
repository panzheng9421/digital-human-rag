package com.view163.digitalhuman.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String dashscopeApiKey = "";
    private String deepseekApiKey = "";
    private String docsDir = "./src/main/resources/docs";
    private String persona = "pan";
    private int topK = 3;
    private int dimension = 1024;
    private double temperature = 0.7;

    // 视频生成：app.video.* 由嵌套 Video 类承接绑定，引擎专属配置按 provider 分组
    private Video video = new Video();

    public static class Video {
        // ===== 通用参数（所有引擎共享）=====
        private String provider = "seedance";    // 出片引擎：seedance / grok / volcengine
        private int seconds = 30;       // 切片时长（秒）默认 30；H3 超出单段 15.1s 自动拆子段拼接
        private String size = "720";    // 对应 API size：480 / 720 / 1080
        private String outputDir = "./output";  // Stage3 切片视频落盘目录（确定性命名 seg_XX.mp4）
        private int retryAttempts = 1;           // 单切片出片失败时的额外重试次数（不含首次）
        private String ratio = "16:9";           // 输出比例（方舟专用，其他引擎忽略）
        private boolean generateAudio = false;   // 是否由模型生成配音（方舟专用）
        private String ffmpegPath = "ffmpeg";  // Stage4 拼接用 ffmpeg 可执行路径
        private List<String> referenceImageUrls = new ArrayList<>();  // 默认多参考图（兜底/阿诺）
        private List<String> panReferenceImageUrls = new ArrayList<>();   // 老潘专属参考图（覆盖默认）
        private List<String> anuoReferenceImageUrls = new ArrayList<>();  // 阿诺专属参考图（覆盖默认）
        // 参考音频（方舟专用）：asset://<音频素材ID>/公网URL/Base64，用于锁定真人音色（口型+声音）。
        // 与参考图同理按 persona 区分；2.5 单段 [2,30]s、最多 10 段、总时长 ≤30s、wav/mp3、≤15MB。
        private List<String> referenceAudioUrls = new ArrayList<>();       // 默认参考音频（兜底）
        private List<String> panReferenceAudioUrls = new ArrayList<>();    // 老潘专属音色（覆盖默认）
        private List<String> anuoReferenceAudioUrls = new ArrayList<>();   // 阿诺专属音色（覆盖默认）

        // ===== 引擎专属配置（按 provider 选一块）=====
        private Seedance seedance = new Seedance();
        private Grok grok = new Grok();
        private Volcengine volcengine = new Volcengine();
        private H3 h3 = new H3();

        /**
         * 本地 H3（MiniMax-H3 r2v，跑在本机 ComfyUI 上）引擎配置。
         * 权重名必须与本机 ComfyUI 的 models 目录里的文件名一致；参考图填本地绝对路径（会自动上传到 ComfyUI input）。
         */
        public static class H3 {
            private String baseUrl = "http://127.0.0.1:8198";  // 本机 ComfyUI 地址
            /**
             * 档位（★ SOP 硬参数，两档不许混用，见 D:/ai/rag/director-desk/30_出片管线/MiniMaxH3_出片SOP.md，
             * 数值来源 = D:/ai/opc/minimax-h3/submit_r2v.py 的 PROFILES/常量，照抄勿改）：
             *   formal 正式档 = 1344×768(0.98MP) + 8 步  + 挂 ref2v 8step LoRA（交付用，画质优先）
             *   test  测试档 = 864×480(0.4MP)  + 4 步  + 挂 ref2v 4step LoRA（试 prompt/构图，画质不作数）
             *   custom 自定义 = 用下面显式填的 width/height/steps/use-lora
             * ⚠️ 禁止说「倍」：只有「测试档 / 正式档」，没有 0.2 倍这种说法。
             */
            private String profile = "formal";
            // ===== 正式档（★ SOP 定稿）=====
            private int formalWidth = 1344;           // 0.98MP，official 768p
            private int formalHeight = 768;
            private int formalSteps = 8;              // 8step LoRA 的蒸馏设计步数（NFE），跑满 = 满血
            private String formalLora = "minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors";
            // ===== 测试档（★ SOP 定稿）=====
            private int testWidth = 864;              // 0.4MP
            private int testHeight = 480;
            private int testSteps = 4;                // 4step LoRA 的蒸馏设计步数
            private String testLora = "minimax_h3_ref2v_turbo_4step_v0.1_comfyui_bf16.safetensors";
            private int singleShotSeconds = 5;  // SOP：单镜默认 5s（项目切片时长应与之对齐）
            /** 超分：vsr = 走 SOP 唯一标准 submit_vsr.py（nvvfx 硬件 VSR）；none = 不超分 */
            private String upscale = "vsr";
            private String vsrScript = "D:/ai/opc/minimax-h3/submit_vsr.py";
            private String pythonPath = "python";
            private String comfyInputDir = "D:/ai/opc/minimax-h3/ComfyUI/input";
            /** submit_vsr.py 里成片落点是硬编码的，跑完从这拷回项目目录 */
            private String vsrOutDir = "D:/ai/video/小白厨房/output_sr";
            /**
             * 参考音频：本地 wav/mp3 绝对路径（会自动拷进 ComfyUI input，走 r2v 的 ref_audios 端口 + prompt 里 <Audio 1>）。
             * ★ SOP「音频驱动口径」：r2v 原生吃音频，H3 直出音轨即交付音轨。
             * 留空 = 走 <d> 台词让 H3 自己发声（口型照样跟，音色是模型的）。
             */
            private List<String> referenceAudios = new ArrayList<>();      // 默认参考音频（兜底）
            private List<String> panReferenceAudios = new ArrayList<>();   // 老潘专属
            private List<String> anuoReferenceAudios = new ArrayList<>();  // 阿诺专属
            private List<String> qipaoReferenceAudios = new ArrayList<>(); // 旗袍主播专属
            /**
             * 是否换回原音轨（ffmpeg）。★ SOP 默认 false：H3 拿到的就是我们的原音频，出片音轨即交付音轨，不做替换。
             * 仅当实测出现音画漂移时才打开（画面 -c:v copy 零重编码，只换音轨）。
             */
            private boolean swapAudio = false;
            private String audioTrack = "";   // 换轨用的原音 wav 绝对路径；swap-audio=true 时才生效
            // 权重（对应本机 D:/ai/opc/minimax-h3/ComfyUI/models 下的实际文件）
            private String unet = "minimax_h3_ref2va_pruned_int8_convrot.safetensors";
            private String clip = "qwen3vl_32b_minimax_h3_nvfp4_awq.safetensors";
            private String vaeVideo = "minimax_h3_video_vae_fp16.safetensors";
            private String vaeAudio = "minimax_h3_audio_vae_fp32.safetensors";
            // 以下 lora / steps / width / height / use-lora 只在 profile=custom 时生效；
            // formal / test 两档一律走上面的 formal*/test* 硬参数（SOP 定稿，不许混用）
            private String lora = "minimax_h3_ref2v_turbo_8step_v1.0_768p_comfyui_bf16.safetensors";
            private boolean useLora = true;
            private double loraStrength = 1.0;
            private int width = 1344;             // 官方 768p 最高档（16:9）
            private int height = 768;
            private int fps = 24;
            private int steps = 8;
            private String sampler = "res_multistep";  // 官方 r2v 固定
            private String scheduler = "simple";
            private double denoise = 1.0;
            private long seedBase = 20692348223064L;   // 固定种子基址，可复现
            private int minFrames = 124;          // 训练下限 ≈5.2s
            private int maxFrames = 362;          // 训练上限 ≈15.1s；超出的切片会自动拆子段再拼接
            private int timeoutSeconds = 7200;    // 单子段轮询上限（768p 20 步很慢）
            /**
             * 参考图送入视觉塔的方式（★ SOP 2026-10-21「原图」口径，默认 max）：
             *   max   = 按参考管线的 2048px 短边送入（身份最强；短边 ≤2048 的参考图原样不压），代价是慢数倍
             *   match = 缩到生成画布的像素面积（4k/2k 大图归一成 880×1168），快
             * 仅是节点推理时的送入方式，磁盘上的图文件原样不动。
             */
            private String refImageSize = "max";
            private List<String> referenceImages = new ArrayList<>();      // 默认参考图（本地绝对路径）
            private List<String> panReferenceImages = new ArrayList<>();   // 老潘专属
            private List<String> anuoReferenceImages = new ArrayList<>();  // 阿诺专属
            private List<String> qipaoReferenceImages = new ArrayList<>(); // 旗袍主播专属（双图：人物+背景）

            /**
             * ★ 场景锁定（2026-10-21 加）：把「背景/机位/光线」写死成固定文字，每镜原样注入 prompt，
             *   绕开 LLM —— 富化是逐镜独立调模型的，背景交给它写必然每镜漂移。
             *   这是纯文本资产，改这里就能换场景，不用动代码。留空 = 不注入（沿用旧行为）。
             */
            private String sceneLock = "";         // 默认（兜底）
            private String panSceneLock = "";      // 老潘专属
            private String anuoSceneLock = "";     // 阿诺专属
            private String qipaoSceneLock = "";    // 旗袍主播专属（主播书房）

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getProfile() { return profile; }
            public void setProfile(String profile) { this.profile = profile; }
            public int getTestWidth() { return testWidth; }
            public void setTestWidth(int testWidth) { this.testWidth = testWidth; }
            public int getTestHeight() { return testHeight; }
            public void setTestHeight(int testHeight) { this.testHeight = testHeight; }
            public int getTestSteps() { return testSteps; }
            public void setTestSteps(int testSteps) { this.testSteps = testSteps; }
            public String getTestLora() { return testLora; }
            public void setTestLora(String testLora) { this.testLora = testLora; }
            public int getFormalWidth() { return formalWidth; }
            public void setFormalWidth(int formalWidth) { this.formalWidth = formalWidth; }
            public int getFormalHeight() { return formalHeight; }
            public void setFormalHeight(int formalHeight) { this.formalHeight = formalHeight; }
            public int getFormalSteps() { return formalSteps; }
            public void setFormalSteps(int formalSteps) { this.formalSteps = formalSteps; }
            public String getFormalLora() { return formalLora; }
            public void setFormalLora(String formalLora) { this.formalLora = formalLora; }
            public boolean isSwapAudio() { return swapAudio; }
            public void setSwapAudio(boolean swapAudio) { this.swapAudio = swapAudio; }
            public List<String> getReferenceAudios() { return referenceAudios; }
            public void setReferenceAudios(List<String> referenceAudios) { this.referenceAudios = referenceAudios; }
            public List<String> getPanReferenceAudios() { return panReferenceAudios; }
            public void setPanReferenceAudios(List<String> panReferenceAudios) { this.panReferenceAudios = panReferenceAudios; }
            public List<String> getAnuoReferenceAudios() { return anuoReferenceAudios; }
            public void setAnuoReferenceAudios(List<String> anuoReferenceAudios) { this.anuoReferenceAudios = anuoReferenceAudios; }
            public List<String> getQipaoReferenceAudios() { return qipaoReferenceAudios; }
            public void setQipaoReferenceAudios(List<String> qipaoReferenceAudios) { this.qipaoReferenceAudios = qipaoReferenceAudios; }
            public int getSingleShotSeconds() { return singleShotSeconds; }
            public void setSingleShotSeconds(int singleShotSeconds) { this.singleShotSeconds = singleShotSeconds; }
            public String getUpscale() { return upscale; }
            public void setUpscale(String upscale) { this.upscale = upscale; }
            public String getVsrScript() { return vsrScript; }
            public void setVsrScript(String vsrScript) { this.vsrScript = vsrScript; }
            public String getPythonPath() { return pythonPath; }
            public void setPythonPath(String pythonPath) { this.pythonPath = pythonPath; }
            public String getComfyInputDir() { return comfyInputDir; }
            public void setComfyInputDir(String comfyInputDir) { this.comfyInputDir = comfyInputDir; }
            public String getVsrOutDir() { return vsrOutDir; }
            public void setVsrOutDir(String vsrOutDir) { this.vsrOutDir = vsrOutDir; }
            public String getAudioTrack() { return audioTrack; }
            public void setAudioTrack(String audioTrack) { this.audioTrack = audioTrack; }
            public String getUnet() { return unet; }
            public void setUnet(String unet) { this.unet = unet; }
            public String getClip() { return clip; }
            public void setClip(String clip) { this.clip = clip; }
            public String getVaeVideo() { return vaeVideo; }
            public void setVaeVideo(String vaeVideo) { this.vaeVideo = vaeVideo; }
            public String getVaeAudio() { return vaeAudio; }
            public void setVaeAudio(String vaeAudio) { this.vaeAudio = vaeAudio; }
            public String getLora() { return lora; }
            public void setLora(String lora) { this.lora = lora; }
            public boolean isUseLora() { return useLora; }
            public void setUseLora(boolean useLora) { this.useLora = useLora; }
            public double getLoraStrength() { return loraStrength; }
            public void setLoraStrength(double loraStrength) { this.loraStrength = loraStrength; }
            public int getWidth() { return width; }
            public void setWidth(int width) { this.width = width; }
            public int getHeight() { return height; }
            public void setHeight(int height) { this.height = height; }
            public int getFps() { return fps; }
            public void setFps(int fps) { this.fps = fps; }
            public int getSteps() { return steps; }
            public void setSteps(int steps) { this.steps = steps; }
            public String getSampler() { return sampler; }
            public void setSampler(String sampler) { this.sampler = sampler; }
            public String getScheduler() { return scheduler; }
            public void setScheduler(String scheduler) { this.scheduler = scheduler; }
            public double getDenoise() { return denoise; }
            public void setDenoise(double denoise) { this.denoise = denoise; }
            public long getSeedBase() { return seedBase; }
            public void setSeedBase(long seedBase) { this.seedBase = seedBase; }
            public int getMinFrames() { return minFrames; }
            public void setMinFrames(int minFrames) { this.minFrames = minFrames; }
            public int getMaxFrames() { return maxFrames; }
            public void setMaxFrames(int maxFrames) { this.maxFrames = maxFrames; }
            public int getTimeoutSeconds() { return timeoutSeconds; }
            public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
            public String getRefImageSize() { return refImageSize; }
            public void setRefImageSize(String refImageSize) { this.refImageSize = refImageSize; }
            public List<String> getReferenceImages() { return referenceImages; }
            public void setReferenceImages(List<String> referenceImages) { this.referenceImages = referenceImages; }
            public List<String> getPanReferenceImages() { return panReferenceImages; }
            public void setPanReferenceImages(List<String> panReferenceImages) { this.panReferenceImages = panReferenceImages; }
            public List<String> getAnuoReferenceImages() { return anuoReferenceImages; }
            public void setAnuoReferenceImages(List<String> anuoReferenceImages) { this.anuoReferenceImages = anuoReferenceImages; }
            public List<String> getQipaoReferenceImages() { return qipaoReferenceImages; }
            public void setQipaoReferenceImages(List<String> qipaoReferenceImages) { this.qipaoReferenceImages = qipaoReferenceImages; }

            /** 按 persona 选参考图：有专属用专属，否则回落默认；都为空则由引擎退化为纯文生视频。 */
            public List<String> getReferenceImagesFor(String persona) {
                List<String> specific = "pan".equalsIgnoreCase(persona) ? panReferenceImages
                        : "anuo".equalsIgnoreCase(persona) ? anuoReferenceImages
                        : "qipao".equalsIgnoreCase(persona) ? qipaoReferenceImages : referenceImages;
                if (specific != null && !specific.isEmpty()) return specific;
                return referenceImages == null ? List.of() : referenceImages;
            }

            public String getSceneLock() { return sceneLock; }
            public void setSceneLock(String sceneLock) { this.sceneLock = sceneLock; }
            public String getPanSceneLock() { return panSceneLock; }
            public void setPanSceneLock(String panSceneLock) { this.panSceneLock = panSceneLock; }
            public String getAnuoSceneLock() { return anuoSceneLock; }
            public void setAnuoSceneLock(String anuoSceneLock) { this.anuoSceneLock = anuoSceneLock; }
            public String getQipaoSceneLock() { return qipaoSceneLock; }
            public void setQipaoSceneLock(String qipaoSceneLock) { this.qipaoSceneLock = qipaoSceneLock; }

            /** 按 persona 选场景锁定文本：有专属用专属，否则回落默认；都为空返回 null（调用方跳过注入）。 */
            public String getSceneLockFor(String persona) {
                String specific = "pan".equalsIgnoreCase(persona) ? panSceneLock
                        : "anuo".equalsIgnoreCase(persona) ? anuoSceneLock
                        : "qipao".equalsIgnoreCase(persona) ? qipaoSceneLock : sceneLock;
                if (specific != null && !specific.isBlank()) return specific;
                return (sceneLock == null || sceneLock.isBlank()) ? null : sceneLock;
            }

            /**
             * 按 persona 选参考音频（有序）：工作台上传的「台词序列」优先，否则单条覆盖，否则配置文件。
             *
             * ★ 2026-10-21 老板口径修正：配音不是「选一条当整片时长基准」，而是**按台词分句排列**——
             *   第 1 条=第一句台词、第 2 条=第二句台词…切片按句数分、第 N 段出片只挂第 N 条音频。
             *   所以这里返回的是**有序列表**，调用方按段序取第 index 条（见 audioSecondsFor / stageReferenceAudios）。
             */
            public List<String> getReferenceAudiosFor(String persona) {
                List<String> seq = AppProperties.getAudioSequence(persona);
                if (seq != null && !seq.isEmpty()) return seq;
                String override = AppProperties.getAudioOverride(persona);
                if (override != null && !override.isBlank()) return List.of(override);
                return getConfiguredAudiosFor(persona);
            }

            /** 配置文件里的原始配音列表（不含运行时覆盖），供配音页列出候选 */
            public List<String> getConfiguredAudiosFor(String persona) {
                List<String> specific = "pan".equalsIgnoreCase(persona) ? panReferenceAudios
                        : "anuo".equalsIgnoreCase(persona) ? anuoReferenceAudios
                        : "qipao".equalsIgnoreCase(persona) ? qipaoReferenceAudios : referenceAudios;
                if (specific != null && !specific.isEmpty()) return specific;
                return referenceAudios == null ? List.of() : referenceAudios;
            }
        }

        public static class Seedance {
            private String baseUrl = "https://new.xlcsh.top/v1/videos";
            private String apiKey = "";
            private String modelId = "seedance-2.0-mini";  // 测试用轻量模型；正式出片可换 seedance-2.5

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getModelId() { return modelId; }
            public void setModelId(String modelId) { this.modelId = modelId; }
        }

        public static class Grok {
            private String apiKey = "";          // Grok 引擎密钥；为空时回退读环境变量 XAI_API_KEY
            private String baseUrl = "https://api.x.ai";  // Grok API 基地址；中转站时改为 https://sub.xlcsh.top
            private List<String> referenceAudioIds = new ArrayList<>();  // Grok 预设人声 voice_id 列表（如 sal），最多 3 个

            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public List<String> getReferenceAudioIds() { return referenceAudioIds; }
            public void setReferenceAudioIds(List<String> referenceAudioIds) { this.referenceAudioIds = referenceAudioIds; }
        }

        public static class Volcengine {
            private String apiKey = "";          // 火山方舟 API Key；为空时回退读环境变量 ARK_API_KEY
            private String baseUrl = "https://ark.cn-beijing.volces.com";  // 火山方舟 API 基地址（勿带末尾斜杠）
            private String modelId = "doubao-seedance-2-5";  // 火山方舟视频模型 ID

            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
            public String getModelId() { return modelId; }
            public void setModelId(String modelId) { this.modelId = modelId; }
        }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public int getSeconds() { return seconds; }
        public void setSeconds(int seconds) { this.seconds = seconds; }

        /**
         * ★ 配音驱动时长（2026-10-21 老板口径）：老板的工作流是「先有配音、后让 H3 出完整视频」，
         * 所以 persona 配了参考音频时，切片/富化/出片的时长一律按音频实际长度（向上取整秒），
         * 不锁死 app.video.seconds；没配音频返回 null（调用方自行用配置秒数）。
         * index = 第几段（1 起始）：配了多条音频时按顺序一一对应，越界回退第 1 条。
         */
        public Integer audioSecondsFor(String persona) { return audioSecondsFor(persona, 1); }

        public Integer audioSecondsFor(String persona, int index) {
            if (h3 == null) return null;
            List<String> auds = h3.getReferenceAudiosFor(persona);
            if (auds == null || auds.isEmpty()) return null;
            String f = (index >= 1 && index <= auds.size()) ? auds.get(index - 1) : auds.get(0);
            return probeAudioSeconds(f, ffmpegPath);
        }

        /**
         * 整条片子的配音总时长：各句向上取整后相加。
         * 为什么逐句 ceil 而不是「总和再 ceil」：出片是逐段跑的，每段各自 ceil 才能保证
         * 每句配音响完（段长 ≥ 音频长），逐段相加才是最终成片的真实长度。
         * 没配音返回 null。
         */
        public Integer totalAudioSeconds(String persona) {
            if (h3 == null) return null;
            List<String> auds = h3.getReferenceAudiosFor(persona);
            if (auds == null || auds.isEmpty()) return null;
            int sum = 0;
            for (String f : auds) {
                Integer s = probeAudioSeconds(f, ffmpegPath);
                if (s == null || s <= 0) return null;   // 有任何一句探测失败就别拿假总数去切片
                sum += s;
            }
            return sum > 0 ? sum : null;
        }

        /** 配音句数（没有配音返回 0） */
        public int audioCount(String persona) {
            if (h3 == null) return 0;
            List<String> auds = h3.getReferenceAudiosFor(persona);
            return auds == null ? 0 : auds.size();
        }

        public String getSize() { return size; }
        public void setSize(String size) { this.size = size; }

        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

        public int getRetryAttempts() { return retryAttempts; }
        public void setRetryAttempts(int retryAttempts) { this.retryAttempts = retryAttempts; }

        public String getRatio() { return ratio; }
        public void setRatio(String ratio) { this.ratio = ratio; }

        public boolean isGenerateAudio() { return generateAudio; }
        public void setGenerateAudio(boolean generateAudio) { this.generateAudio = generateAudio; }

        public String getFfmpegPath() { return ffmpegPath; }
        public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }

        public List<String> getReferenceImageUrls() { return referenceImageUrls; }
        public void setReferenceImageUrls(List<String> referenceImageUrls) { this.referenceImageUrls = referenceImageUrls; }

        public List<String> getPanReferenceImageUrls() { return panReferenceImageUrls; }
        public void setPanReferenceImageUrls(List<String> panReferenceImageUrls) { this.panReferenceImageUrls = panReferenceImageUrls; }

        public List<String> getAnuoReferenceImageUrls() { return anuoReferenceImageUrls; }
        public void setAnuoReferenceImageUrls(List<String> anuoReferenceImageUrls) { this.anuoReferenceImageUrls = anuoReferenceImageUrls; }

        public List<String> getReferenceAudioUrls() { return referenceAudioUrls; }
        public void setReferenceAudioUrls(List<String> referenceAudioUrls) { this.referenceAudioUrls = referenceAudioUrls; }

        public List<String> getPanReferenceAudioUrls() { return panReferenceAudioUrls; }
        public void setPanReferenceAudioUrls(List<String> panReferenceAudioUrls) { this.panReferenceAudioUrls = panReferenceAudioUrls; }

        public List<String> getAnuoReferenceAudioUrls() { return anuoReferenceAudioUrls; }
        public void setAnuoReferenceAudioUrls(List<String> anuoReferenceAudioUrls) { this.anuoReferenceAudioUrls = anuoReferenceAudioUrls; }

        /** 按 persona 选生效的参考音频：有专属配置用专属，否则回落默认。 */
        public List<String> getReferenceAudioUrlsFor(String persona) {
            List<String> specific = "pan".equalsIgnoreCase(persona) ? panReferenceAudioUrls
                    : "anuo".equalsIgnoreCase(persona) ? anuoReferenceAudioUrls : referenceAudioUrls;
            if (specific != null && !specific.isEmpty()) return specific;
            return referenceAudioUrls == null ? List.of() : referenceAudioUrls;
        }

        /** 按 persona 选生效的参考图：有专属配置用专属，否则回落默认。 */
        public List<String> getReferenceImageUrlsFor(String persona) {
            List<String> specific = "pan".equalsIgnoreCase(persona) ? panReferenceImageUrls
                    : "anuo".equalsIgnoreCase(persona) ? anuoReferenceImageUrls : referenceImageUrls;
            if (specific != null && !specific.isEmpty()) return specific;
            return referenceImageUrls == null ? List.of() : referenceImageUrls;
        }

        public Seedance getSeedance() { return seedance; }
        public void setSeedance(Seedance seedance) { this.seedance = seedance; }

        public Grok getGrok() { return grok; }
        public void setGrok(Grok grok) { this.grok = grok; }

        public Volcengine getVolcengine() { return volcengine; }
        public void setVolcengine(Volcengine volcengine) { this.volcengine = volcengine; }

        public H3 getH3() { return h3; }
        public void setH3(H3 h3) { this.h3 = h3; }
    }

    public Video getVideo() { return video; }
    public void setVideo(Video video) { this.video = video; }

    public String getDashscopeApiKey() {
        return dashscopeApiKey;
    }

    public void setDashscopeApiKey(String dashscopeApiKey) {
        this.dashscopeApiKey = dashscopeApiKey;
    }

    public String getDeepseekApiKey() {
        return deepseekApiKey;
    }

    public void setDeepseekApiKey(String deepseekApiKey) {
        this.deepseekApiKey = deepseekApiKey;
    }

    public String getDocsDir() {
        return docsDir;
    }

    public void setDocsDir(String docsDir) {
        this.docsDir = docsDir;
    }

    public String getPersona() {
        return persona;
    }

    public void setPersona(String persona) {
        this.persona = persona;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /* ────────── 配音实际时长探测（2026-10-21 老板口径：按配音实际时间生成，不锁死秒数）──────────
     * ffprobe 与 app.video.ffmpeg-path 同目录；探测失败返回 null，调用方兜底用配置秒数。
     * 结果按文件路径缓存（进程内），换音频文件后重启进程即刷新。 */
    private static final Map<String, Integer> AUDIO_SECS_CACHE = new ConcurrentHashMap<>();

    public static Integer probeAudioSeconds(String audioPath, String ffmpegPath) {
        if (audioPath == null || audioPath.isBlank()) return null;
        String key = audioPath.trim();
        Integer cached = AUDIO_SECS_CACHE.get(key);
        if (cached != null) return cached > 0 ? cached : null;
        String dir = (ffmpegPath == null || ffmpegPath.isBlank()) ? "" : ffmpegPath.replaceAll("[^/\\\\]+$", "");
        String probe = dir + "ffprobe.exe";
        try {
            Process p = new ProcessBuilder(probe, "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", key).redirectErrorStream(true).start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                AUDIO_SECS_CACHE.put(key, -1);
                return null;
            }
            double d = Double.parseDouble(out.split("\\R")[0].trim());
            if (d <= 0) {
                AUDIO_SECS_CACHE.put(key, -1);
                return null;
            }
            // 向上取整：宁可画面比配音长一点（尾部补环境音），也不能截断配音
            int secs = (int) Math.ceil(d);
            AUDIO_SECS_CACHE.put(key, secs);
            return secs;
        } catch (Exception e) {
            AUDIO_SECS_CACHE.put(key, -1);
            return null;
        }
    }

    /* ────────── 运行时配音覆盖（工作台上传，2026-10-21）──────────
     * 老板要在页面上直接换配音：上传即生效，不用改 yml、不用重启。
     * 按 persona 覆盖；H3.getReferenceAudiosFor() 优先读它，所以切片/富化/出片全自动跟着换。 */
    /* ────────── 运行时配音序列（工作台上传，2026-10-21）──────────
     * 老板要的是「按台词分句」：第一句、第二句、第三句各一条音频，第 N 段镜头挂第 N 条。
     * 所以存的是**有序列表**（AUDIO_SEQUENCE），不是单条覆盖。
     * H3.getReferenceAudiosFor() 优先读它 → 切片/富化/出片全自动按这个顺序走。 */
    private static final Map<String, List<String>> AUDIO_SEQUENCE = new ConcurrentHashMap<>();

    /** 整体设置某 persona 的配音序列（顺序即台词顺序）；传空 = 清掉运行时序列、回落到配置 */
    public static void setAudioSequence(String persona, List<String> paths) {
        if (persona == null) return;
        String p = persona.trim().toLowerCase();
        List<String> clean = new ArrayList<>();
        if (paths != null) {
            for (String s : paths) {
                if (s == null || s.isBlank()) continue;
                String v = s.trim();
                if (clean.contains(v)) continue;     // 同一条不许重复入序列（否则第 N 段会撞车）
                AUDIO_SECS_CACHE.remove(v);          // 换文件了，旧时长缓存作废
                clean.add(v);
            }
        }
        if (clean.isEmpty()) {
            AUDIO_SEQUENCE.remove(p);
            return;
        }
        AUDIO_SEQUENCE.put(p, clean);
    }

    /** 追加一条到序列末尾（上传一句台词就是追加一句） */
    public static void appendAudio(String persona, String path) {
        if (persona == null || path == null || path.isBlank()) return;
        List<String> cur = new ArrayList<>(getAudioSequence(persona));
        cur.add(path.trim());
        setAudioSequence(persona, cur);
    }

    /** 从序列里移除一条（删除音频时同步） */
    public static void removeFromSequence(String persona, String path) {
        if (persona == null || path == null) return;
        List<String> cur = new ArrayList<>(getAudioSequence(persona));
        cur.removeIf(x -> x.equalsIgnoreCase(path.trim()));
        setAudioSequence(persona, cur);
    }

    /** 当前运行时配音序列（可能为空 = 没设过，回落到配置） */
    public static List<String> getAudioSequence(String persona) {
        if (persona == null) return List.of();
        List<String> l = AUDIO_SEQUENCE.get(persona.trim().toLowerCase());
        return l == null ? List.of() : l;
    }

    private static final Map<String, String> AUDIO_OVERRIDE = new ConcurrentHashMap<>();

    public static void setAudioOverride(String persona, String path) {
        if (persona == null) return;
        String p = persona.trim().toLowerCase();
        if (path == null || path.isBlank()) {
            AUDIO_OVERRIDE.remove(p);
            return;
        }
        String v = path.trim();
        AUDIO_SECS_CACHE.remove(v);   // 换文件了，旧时长缓存作废
        AUDIO_OVERRIDE.put(p, v);
    }

    public static void clearAudioOverride(String persona) {
        if (persona != null) AUDIO_OVERRIDE.remove(persona.trim().toLowerCase());
    }

    public static String getAudioOverride(String persona) {
        return persona == null ? null : AUDIO_OVERRIDE.get(persona.trim().toLowerCase());
    }
}
