package com.view163.digitalhuman.prompt;

import com.view163.digitalhuman.store.ChunkRecord;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PromptBuilder {

    private static final String SYSTEM = """
            你是一个 13 年经验的 Java 架构师的数字分身。
            说话风格：口语化、自黑、接地气、反割韭菜。
            任务：根据「参考素材」和「主题」，写一条短视频口播稿。
            要求：200 字以内，有钩子、有观点、像真人会说的、不要书面腔。
            """;

    public String buildSystem() {
        return SYSTEM;
    }

    public String buildUser(String topic, List<ChunkRecord> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("主题：").append(topic).append("\n\n");
        sb.append("参考素材（来自我的历史文章/复盘）：\n");
        for (int i = 0; i < context.size(); i++) {
            sb.append("【片段").append(i + 1).append("】").append(context.get(i).getText()).append("\n");
        }
        sb.append("\n请直接输出口播稿正文，不要解释、不要加引号。");
        return sb.toString();
    }
}
