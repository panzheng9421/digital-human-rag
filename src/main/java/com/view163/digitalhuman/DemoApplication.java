package com.view163.digitalhuman;

import com.view163.digitalhuman.config.AppProperties;
import com.view163.digitalhuman.prompt.EnrichPrompt;
import com.view163.digitalhuman.service.PerformanceEnricher;
import com.view163.digitalhuman.service.ScriptGenerator;
import com.view163.digitalhuman.service.ScriptSlicer;
import com.view163.digitalhuman.service.VideoConcatenator;
import com.view163.digitalhuman.service.VideoGenService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    CommandLineRunner runner(ScriptGenerator generator, PerformanceEnricher enricher,
                             VideoGenService videoGen, VideoConcatenator concatenator,
                             ScriptSlicer slicer, AppProperties props) {
        return args -> {
            String persona = props.getPersona();
            String personaName = "amei".equalsIgnoreCase(persona) ? "阿妹" : "老潘";
            String docsDir = props.getDocsDir() + "/" + persona;
            String topic = args.length > 0 ? String.join(" ", args) : "做一下自我介绍";
            System.out.println("=== 数字分身全流程（稿 -> 富化 -> 切片 -> 出片 -> 拼接）===");
            System.out.println("人设：" + persona + "（知识库：" + docsDir + "）");
            System.out.println("主题：" + topic);

            // Stage1：RAG 生成裸口播稿
            generator.buildKnowledgeBase(docsDir);
            String script = generator.generate(topic, persona);
            System.out.println("\n--- [Stage1] 裸口播稿 ---\n" + script);

            // Stage1.5：切片（每段 ≤30s，在句号边界封口）
            List<String> slices = slicer.slice(script);
            System.out.println("\n--- [Stage1.5] 切片（共 " + slices.size() + " 段，每段≤30s）---");
            for (int i = 0; i < slices.size(); i++) {
                System.out.println("\n[片段 " + (i + 1) + "/" + slices.size() + "] " + slices.get(i));
            }

            // Stage2+3 逐段：表演富化 -> 出片（画面提示 + 本段口播稿）
            List<String> segPaths = new ArrayList<>();
            String prevTail = "";
            for (int i = 0; i < slices.size(); i++) {
                String seg = slices.get(i);
                String segContext = buildSegmentContext(i, slices.size(), seg, prevTail);
                String segEnriched = enricher.enrich(segContext, personaName);
                String segVisual = EnrichPrompt.extractVisual(segEnriched);
                String segPrompt = segVisual + "\n\n[台词]\n" + seg;
                System.out.println("\n--- [Stage3] 片段 " + (i + 1) + " 发给 Seedance 的 prompt ---\n" + segPrompt);
                String segMp4 = videoGen.generate(segPrompt);
                System.out.println("\n--- [Stage3] 片段 " + (i + 1) + " 视频已生成 ---\n" + segMp4);
                segPaths.add(segMp4);
                prevTail = tailOf(seg);
            }

            // Stage4：拼接（单段跳过）
            String finalMp4;
            if (segPaths.size() <= 1) {
                finalMp4 = segPaths.isEmpty() ? "" : segPaths.get(0);
                System.out.println("\n--- [Stage4] 单段无需拼接 ---\n" + finalMp4);
            } else {
                finalMp4 = concatenator.concat(segPaths);
                System.out.println("\n--- [Stage4] 视频已拼接（共 " + segPaths.size() + " 段）---\n" + finalMp4);
            }
        };
    }

    /** 给每段富化补充分段上下文，让 LLM 知道是第几段、承接上文，缓解跨段跳变 */
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

    /** 取段落末尾若干字作为下一段的承接提示 */
    private static String tailOf(String seg) {
        if (seg == null || seg.isEmpty()) return "";
        int start = Math.max(0, seg.length() - 15);
        return seg.substring(start);
    }
}
