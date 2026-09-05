package com.view163.digitalhuman.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
        private int seconds = 5;        // 切片时长（秒），正式分镜可调 10/20/30
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
}
