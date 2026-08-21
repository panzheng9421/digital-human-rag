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

    // 视频生成（中转站 Seedance，New API 协议，见 rag/API.md）
    // yml 路径为 app.video.*，由嵌套 Video 类承接绑定
    private Video video = new Video();

    public static class Video {
        private String relayBaseUrl = "https://new.xlcsh.top/v1/videos";
        private String relayApiKey = "";
        private String modelId = "seedance-2.0-mini";  // 测试用轻量模型；正式出片可换 seedance-2.5
        private List<String> referenceImageUrls = new ArrayList<>();  // 默认多参考图（兜底/阿诺）：儿童房场景背景 + 人物图（按提交顺序）
        private List<String> panReferenceImageUrls = new ArrayList<>();   // 老潘专属参考图（覆盖默认；为空则回落默认）
        private List<String> anuoReferenceImageUrls = new ArrayList<>();  // 阿诺专属参考图（覆盖默认；为空则回落默认）
        private int seconds = 5;        // 测试 5s（mini 封顶）；正式分镜可调 10/20/30
        private String size = "720";    // 对应 API size：480 / 720（输出固定 16:9 横屏）
        private String ffmpegPath = "ffmpeg";  // Stage4 拼接用 ffmpeg 可执行路径，默认取 PATH 中的 ffmpeg
        private String outputDir = "./output";  // Stage3 切片视频落盘目录（确定性命名 seg_XX.mp4，支持断点续跑）
        private int retryAttempts = 1;           // 单切片出片失败时的额外重试次数（不含首次）
        private String provider = "seedance";    // 出片引擎：seedance（默认，经 new.xlcsh.top 中转）/ grok（api.x.ai）
        private String grokApiKey = "";          // Grok 引擎密钥；为空时回退读环境变量 XAI_API_KEY
        private String grokBaseUrl = "https://api.x.ai";  // Grok API 基地址；走中转站时改为如 https://sub.xlcsh.icu（勿带末尾斜杠）
        private List<String> referenceAudioIds = new ArrayList<>();  // Grok 预设人声 voice_id 列表（如 eve），最多 3 个；非空时改用 reference_audios 自合成语音

        public String getRelayBaseUrl() { return relayBaseUrl; }
        public void setRelayBaseUrl(String relayBaseUrl) { this.relayBaseUrl = relayBaseUrl; }

        public String getRelayApiKey() { return relayApiKey; }
        public void setRelayApiKey(String relayApiKey) { this.relayApiKey = relayApiKey; }

        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }

        public List<String> getReferenceImageUrls() { return referenceImageUrls; }
        public void setReferenceImageUrls(List<String> referenceImageUrls) { this.referenceImageUrls = referenceImageUrls; }

        public List<String> getPanReferenceImageUrls() { return panReferenceImageUrls; }
        public void setPanReferenceImageUrls(List<String> panReferenceImageUrls) { this.panReferenceImageUrls = panReferenceImageUrls; }

        public List<String> getAnuoReferenceImageUrls() { return anuoReferenceImageUrls; }
        public void setAnuoReferenceImageUrls(List<String> anuoReferenceImageUrls) { this.anuoReferenceImageUrls = anuoReferenceImageUrls; }

        /** 按 persona 选生效的参考图：有专属配置用专属，否则回落默认。 */
        public List<String> getReferenceImageUrlsFor(String persona) {
            List<String> specific = "pan".equalsIgnoreCase(persona) ? panReferenceImageUrls
                    : "anuo".equalsIgnoreCase(persona) ? anuoReferenceImageUrls : referenceImageUrls;
            if (specific != null && !specific.isEmpty()) return specific;
            return referenceImageUrls == null ? List.of() : referenceImageUrls;
        }

        public int getSeconds() { return seconds; }
        public void setSeconds(int seconds) { this.seconds = seconds; }

        public String getSize() { return size; }
        public void setSize(String size) { this.size = size; }

        public String getFfmpegPath() { return ffmpegPath; }
        public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }

        public String getOutputDir() { return outputDir; }
        public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

        public int getRetryAttempts() { return retryAttempts; }
        public void setRetryAttempts(int retryAttempts) { this.retryAttempts = retryAttempts; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public String getGrokApiKey() { return grokApiKey; }
        public void setGrokApiKey(String grokApiKey) { this.grokApiKey = grokApiKey; }

        public String getGrokBaseUrl() { return grokBaseUrl; }
        public void setGrokBaseUrl(String grokBaseUrl) { this.grokBaseUrl = grokBaseUrl; }

        public List<String> getReferenceAudioIds() { return referenceAudioIds; }
        public void setReferenceAudioIds(List<String> referenceAudioIds) { this.referenceAudioIds = referenceAudioIds; }
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
