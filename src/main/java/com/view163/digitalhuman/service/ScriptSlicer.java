package com.view163.digitalhuman.service;

import com.view163.digitalhuman.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage1.5 切片：把完整口播稿切成 N 段，每段字数 ≤ seconds × CHARS_PER_SECOND，
 * 使每段天然匹配对应秒数的视频片段（切片粒度与 Video.seconds 自动联动）。
 *
 * 三级切片策略：
 *   1) 优先在主标点（。！？；\n）处断句
 *   2) 单句超 maxChars 时，在次级标点（，、：——）处强制截断
 *   3) 结果 < maxChars×0.4（过短）时，贪心合并下一段，避免 5s 视频只说 4 个字
 */
@Component
public class ScriptSlicer {

    private static final int CHARS_PER_SECOND = 4;  // 日常口播语速 ~240 字/分
    private static final double MIN_FILL_RATIO = 0.4;  // 段最短不低于上限的 40%

    /** 主标点：自然断句位置 */
    private static final String PRIMARY_DELIMS = "。！？；\n";
    /** 次级标点：长句强制截断位置（优先级从高到低） */
    private static final String SECONDARY_DELIMS = "，、：——";

    private final int maxChars;
    private final int minChars;

    public ScriptSlicer(AppProperties props) {
        int seconds = props.getVideo().getSeconds();
        // seconds 异常（≤0）时兜底 120 字（≈30s），避免除零/负数导致整稿变一段
        this.maxChars = seconds > 0 ? seconds * CHARS_PER_SECOND : 120;
        this.minChars = (int) Math.max(8, Math.round(maxChars * MIN_FILL_RATIO));
    }

    public List<String> slice(String script) {
        List<String> result = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return result;
        }

        // Step 1: 按主标点切句 → 再对超长句按次级标点截断 → 得到原子片段列表
        List<String> atoms = splitIntoAtoms(script);

        // Step 2: 贪心合并，每段 ≤ maxChars，合并后 ≥ minChars（末段除外）
        StringBuilder buf = new StringBuilder();
        for (String atom : atoms) {
            if (buf.length() > 0 && buf.length() + atom.length() > maxChars) {
                String seg = buf.toString().trim();
                if (!seg.isEmpty()) {
                    result.add(seg);
                }
                buf.setLength(0);
            }
            buf.append(atom);
        }
        if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }

        // Step 3: 后处理 —— 过短段（非末段）与下一段合并
        return mergeShortSegments(result);
    }

    // ── Step 1: 原子化分割 ──────────────────────────────────

    /**
     * 两级切分：
     * 1) 先按主标点断句
     * 2) 对超过 maxChars 的单句，再按次级标点截成多段
     */
    private List<String> splitIntoAtoms(String text) {
        List<String> sentences = splitByDelims(text, PRIMARY_DELIMS);
        List<String> atoms = new ArrayList<>();
        for (String s : sentences) {
            if (s.length() <= maxChars) {
                atoms.add(s);
            } else {
                // 超长句：按次级标点截断
                atoms.addAll(splitLongSentence(s));
            }
        }
        return atoms;
    }

    /** 按指定字符集分割文本，保留分隔符本身 */
    private List<String> splitByDelims(String text, String delims) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (delims.indexOf(c) >= 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * 超长句截断策略：
     * - 按次级标点（，、：——）逐段累积
     * - 累积超 maxChars 时封口
     * - 如果次级标点间仍有一段超 maxChars（无标点的长文字块），硬切 maxChars
     */
    private List<String> splitLongSentence(String sentence) {
        List<String> parts = splitByDelims(sentence, SECONDARY_DELIMS);
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : parts) {
            if (buf.length() > 0 && buf.length() + p.length() > maxChars) {
                chunks.add(buf.toString().trim());
                buf.setLength(0);
            }
            buf.append(p);
            // 无标点的纯文字块可能超限，硬切
            if (buf.length() > maxChars) {
                chunks.add(buf.substring(0, maxChars).trim());
                buf = new StringBuilder(buf.substring(maxChars));
            }
        }
        if (buf.length() > 0) {
            chunks.add(buf.toString().trim());
        }
        return chunks;
    }

    // ── Step 3: 过短段合并 ───────────────────────────────────

    /**
     * 从头扫描，若某段（非最后一段）长度 < minChars，
     * 则与下一段合并。重复直到所有非末段都 ≥ minChars。
     */
    private List<String> mergeShortSegments(List<String> segments) {
        if (segments.size() <= 1) {
            return segments;
        }
        List<String> merged = new ArrayList<>(segments);
        boolean changed;
        do {
            changed = false;
            List<String> next = new ArrayList<>();
            for (int i = 0; i < merged.size(); i++) {
                String seg = merged.get(i);
                // 最后一段不参与合并（没有下一段可合）
                if (i == merged.size() - 1) {
                    next.add(seg);
                    continue;
                }
                if (seg.length() < minChars) {
                    // 与下一段合并
                    String combined = seg + merged.get(i + 1);
                    next.add(combined);
                    changed = true;
                    i++;  // 跳过已合并的下一段
                } else {
                    next.add(seg);
                }
            }
            merged = next;
        } while (changed);  // 合并后可能产生新的短段（两短合一变中），继续扫
        return merged;
    }
}
