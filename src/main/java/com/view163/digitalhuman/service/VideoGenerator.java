package com.view163.digitalhuman.service;

/**
 * 视频生成引擎统一契约：给定「画面提示 + 台词」的视觉 prompt，产出一段本地 MP4 的绝对路径。
 * 目前四个实现：
 *   - VideoGenService        ：Seedance（经 new.xlcsh.top 中转站，默认）
 *   - GrokVideoGenService    ：Grok grok-imagine-video-1.5（api.x.ai / 中转站）
 *   - VolcengineVideoGenService：火山方舟 doubao-seedance-2-5（官方直连 ark.cn-beijing.volces.com）
 *   - H3VideoGenService      ：本地 MiniMax-H3 r2v（本机 ComfyUI，单段 ≤15s，超长自动拆子段拼接）
 * 由 DemoApplication 的 videoGenerator() 工厂 Bean 按 app.video.provider 选择。
 */
public interface VideoGenerator {
    String generate(String visualPrompt, int segIndex, int total) throws Exception;
}
