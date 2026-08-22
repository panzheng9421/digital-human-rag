package com.view163.digitalhuman;

import com.view163.digitalhuman.config.AppProperties;
import com.view163.digitalhuman.prompt.EnrichPrompt;
import com.view163.digitalhuman.service.PerformanceEnricher;
import com.view163.digitalhuman.service.ScriptGenerator;
import com.view163.digitalhuman.service.ScriptSlicer;
import com.view163.digitalhuman.service.VideoConcatenator;
import com.view163.digitalhuman.service.VideoGenService;
import com.view163.digitalhuman.service.GrokVideoGenService;
import com.view163.digitalhuman.service.VolcengineVideoGenService;
import com.view163.digitalhuman.service.VideoGenerator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    CommandLineRunner runner(ScriptGenerator generator, PerformanceEnricher enricher,
                             VideoGenerator videoGen, VideoConcatenator concatenator,
                             ScriptSlicer slicer, AppProperties props, EnrichPrompt enrichPrompt) {
        return args -> {
            String persona = props.getPersona();
            String personaName = "anuo".equalsIgnoreCase(persona) ? "阿诺" : "老潘";
            String docsDir = props.getDocsDir() + "/" + persona;

            // 入口分支：--raw=口播稿 或 --raw-file=路径 => 直接输入裸稿，跳过 Stage1 RAG 写稿
            String raw = parseRawScript(args);
            String script;
            if (raw != null) {
                script = raw;
                System.out.println("=== 数字分身（直接输入裸口播稿，跳过 Stage1 RAG 写稿）===");
                System.out.println("人设：" + persona);
                System.out.println("\n--- [Stage1] 裸口播稿（直接输入）---\n" + script);
            } else {
                String topic = args.length > 0 ? String.join(" ", args) : "做一下自我介绍";
                System.out.println("=== 数字分身全流程（稿 -> 富化 -> 切片 -> 出片 -> 拼接）===");
                System.out.println("人设：" + persona + "（知识库：" + docsDir + "）");
                System.out.println("主题：" + topic);

                // Stage1：RAG 生成裸口播稿
                generator.buildKnowledgeBase(docsDir);
                script = generator.generate(topic, persona);
                System.out.println("\n--- [Stage1] 裸口播稿 ---\n" + script);
            }

            // Stage1.5：切片（每段 ≤ video.seconds 对应字数，在句号边界封口，粒度随 seconds 联动）
            List<String> slices = slicer.slice(script);
            System.out.println("\n--- [Stage1.5] 切片（共 " + slices.size() + " 段，每段 ≤ video.seconds 联动字数）---");
            for (int i = 0; i < slices.size(); i++) {
                System.out.println("\n[片段 " + (i + 1) + "/" + slices.size() + "] " + slices.get(i));
            }

            // Stage2+3 逐段：表演富化 -> 出片（画面提示 + 本段口播稿）
            // 容错 + 断点续跑：切片视频落盘为确定性文件名 seg_XX.mp4；
            //   - 已存在则跳过出片（续跑）；
            //   - 出片抛异常（含重试耗尽）则记日志、跳过该段、继续后续切片，绝不拖垮整批。
            Path outDir = Paths.get(props.getVideo().getOutputDir());
            Files.createDirectories(outDir);
            List<String> segPaths = new ArrayList<>();
            String prevTail = "";
            int failed = 0;
            for (int i = 0; i < slices.size(); i++) {
                String seg = slices.get(i);
                String segContext = buildSegmentContext(i, slices.size(), seg, prevTail);
                String segEnriched = enricher.enrich(segContext, personaName, persona);
                String segVisual = enrichPrompt.extractVisual(segEnriched);
                String segPrompt = segVisual + "\n\n[台词]\n" + seg;
                System.out.println("\n--- [Stage3] 片段 " + (i + 1) + "/" + slices.size() + " 发给视频引擎的 prompt ---\n" + segPrompt);

                Path segFile = outDir.resolve(String.format("seg_%02d.mp4", i + 1));
                String segMp4;
                if (Files.exists(segFile)) {
                    System.out.println("[续跑] 片段 " + (i + 1) + " 已存在，跳过出片：" + segFile.toAbsolutePath());
                    segMp4 = segFile.toAbsolutePath().toString();
                } else {
                    try {
                        segMp4 = videoGen.generate(segPrompt, i + 1, slices.size());
                    } catch (Exception e) {
                        failed++;
                        System.out.println("[警告] 片段 " + (i + 1) + " 出片失败，跳过后续重试交由下轮续跑：" + e.getMessage());
                        prevTail = tailOf(seg);
                        continue;
                    }
                }
                System.out.println("\n--- [Stage3] 片段 " + (i + 1) + " 视频已生成 ---\n" + segMp4);
                segPaths.add(segMp4);
                prevTail = tailOf(seg);
            }

            // Stage4：拼接（单段跳过；失败片段不参与，仅拼已成功片段）
            if (failed > 0) {
                System.out.println("\n[汇总] 共 " + slices.size() + " 段，成功 " + segPaths.size() + " 段，失败 " + failed + " 段（重跑可续跑补齐）");
            }
            String finalMp4;
            if (segPaths.isEmpty()) {
                finalMp4 = "";
                System.out.println("\n--- [Stage4] 无成功片段，未生成视频 ---");
            } else if (segPaths.size() <= 1) {
                finalMp4 = segPaths.get(0);
                System.out.println("\n--- [Stage4] 单段无需拼接 ---\n" + finalMp4);
            } else {
                finalMp4 = concatenator.concat(segPaths);
                System.out.println("\n--- [Stage4] 视频已拼接（共 " + segPaths.size() + " 段）---\n" + finalMp4);
            }
        };
    }

    /** 引擎工厂：按 app.video.provider 选择出片实现（seedance 默认 / grok）。 */
    @Bean
    VideoGenerator videoGenerator(AppProperties props) {
        String provider = props.getVideo().getProvider();
        String persona = props.getPersona();
        if ("grok".equalsIgnoreCase(provider)) {
            System.out.println("[引擎] 使用 Grok（grok-imagine-video-1.5）出片");
            return new GrokVideoGenService(props, persona);
        }
        if ("volcengine".equalsIgnoreCase(provider)) {
            System.out.println("[引擎] 使用火山方舟 Seedance 2.5（官方直连）出片");
            return new VolcengineVideoGenService(props, persona);
        }
        System.out.println("[引擎] 使用 Seedance（new.xlcsh.top 中转）出片");
        return new VideoGenService(props, persona);
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

    /**
     * 入口解析：检测是否直接输入裸口播稿（跳过 Stage1）。
     *   --raw=整段口播稿            命令行内联，适合短稿（含空格需引号包裹）
     *   --raw-file=/path/script.txt 从文件读取，适合长稿（支持换行）
     * 命中返回稿子；否则返回 null（走原 RAG 流程）。
     */
    private static String parseRawScript(String[] args) {
        for (String a : args) {
            if (a.startsWith("--raw-file=")) {
                String p = a.substring("--raw-file=".length());
                try {
                    return Files.readString(Paths.get(p)).strip();
                } catch (Exception e) {
                    throw new RuntimeException("读取裸口播稿文件失败：" + p, e);
                }
            }
            if (a.startsWith("--raw=")) {
                return a.substring("--raw=".length());
            }
        }
        return null;
    }
}
