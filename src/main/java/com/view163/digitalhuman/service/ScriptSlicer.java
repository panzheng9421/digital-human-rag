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
 *   3) 结果 < maxChars×0.65（过短）时，贪心合并下一段，避免视频大半静默
 *
 * 英文保护：硬切时自动回溯到单词边界，不会把 seedream 切成 seedre/am、
 *   text-embedding 切成 embedding/-v3
 */
@Component
public class ScriptSlicer {

    private static final int CHARS_PER_SECOND = 5;  // 口播语速 ~300 字/分（稍快时自然语速）
    private static final double MIN_FILL_RATIO = 0.65;  // 段最短不低于上限的 65%（避免10s视频只说20个字）

    /** 主标点：自然断句位置 */
    private static final String PRIMARY_DELIMS = "。！？；\n";
    /** 次级标点：长句强制截断位置（优先级从高到低） */
    private static final String SECONDARY_DELIMS = "，、：——";

    private final int defaultSeconds;

    public ScriptSlicer(AppProperties props) {
        int seconds = props.getVideo().getSeconds();
        // seconds 异常（≤0）时兜底 120 字（≈30s），避免除零/负数导致整稿变一段
        this.defaultSeconds = seconds > 0 ? seconds : 24;
    }

    /** 按配置的 app.video.seconds 切片（命令行管线用） */
    public List<String> slice(String script) {
        return slice(script, defaultSeconds);
    }

    /**
     * 按指定秒数切片（向导式出片用，前端 5-30s 动态选择）。
     * 每段字数上限 = seconds × CHARS_PER_SECOND，粒度随秒数联动。
     */
    public List<String> slice(String script, int seconds) {
        int maxChars = seconds > 0 ? seconds * CHARS_PER_SECOND : defaultSeconds * CHARS_PER_SECOND;
        int minChars = (int) Math.max(15, Math.round(maxChars * MIN_FILL_RATIO));

        List<String> result = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return result;
        }

        // Step 1: 按主标点切句 → 再对超长句按次级标点截断 → 得到原子片段列表
        List<String> atoms = splitIntoAtoms(script, maxChars);

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

        // Step 3: 后处理 —— 过短段（非末段）与下一段合并；末段过短则向上并入倒数第二段
        return mergeShortSegments(result, minChars);
    }

    // ── Step 1: 原子化分割 ──────────────────────────────────

    /**
     * 两级切分：
     * 1) 先按主标点断句
     * 2) 对超过 maxChars 的单句，再按次级标点截成多段
     */
    private List<String> splitIntoAtoms(String text, int maxChars) {
        List<String> sentences = splitByDelims(text, PRIMARY_DELIMS);
        List<String> atoms = new ArrayList<>();
        for (String s : sentences) {
            if (s.length() <= maxChars) {
                atoms.add(s);
            } else {
                // 超长句：按次级标点截断
                atoms.addAll(splitLongSentence(s, maxChars));
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
    private List<String> splitLongSentence(String sentence, int maxChars) {
        List<String> parts = splitByDelims(sentence, SECONDARY_DELIMS);
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : parts) {
            if (buf.length() > 0 && buf.length() + p.length() > maxChars) {
                chunks.add(buf.toString().trim());
                buf.setLength(0);
            }
            buf.append(p);
            // 无标点的纯文字块可能超限，硬切（保护英文单词不在中间断开）
            if (buf.length() > maxChars) {
                int cutPos = findWordBoundary(buf.toString(), maxChars);
                chunks.add(buf.substring(0, cutPos).trim());
                buf = new StringBuilder(buf.substring(cutPos));
            }
        }
        if (buf.length() > 0) {
            chunks.add(buf.toString().trim());
        }
        return chunks;
    }

    // ── 单词边界保护 ───────────────────────────────────────

    /**
     * 硬切时保护英文单词/技术术语不在中间断开。
     * 如果 cutPos 落在 [a-zA-Z0-9._-] 序列中间，向前回溯到最近的词边界。
     * 最少保留 maxChars 的 60%，防止回溯过多导致该段过长。
     */
    private int findWordBoundary(String text, int cutPos) {
        int minCut = Math.max(1, (int) (cutPos * 0.6));
        int pos = cutPos;
        // 如果切点在单词字符中间，往前找词边界
        while (pos > minCut && isWordChar(text.charAt(pos - 1))) {
            pos--;
        }
        return Math.max(minCut, pos);
    }

    private boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
    }

    // ── 按配音分句切（2026-10-21 老板口径：一句台词一条音频）────────────────

    /**
     * 按「每句配音的秒数」分段：**段数 = 配音条数**，第 i 段字数按该句秒数占比分配。
     *
     * 为什么不再按统一秒数切：老板的工作流是「第一句台词、第二句台词、第三句台词各一条音频」，
     * 每句长短不一（3s / 7s / 12s），出片时第 N 段只挂第 N 条音频。若按统一秒数切文案，
     * 段数和音频条数对不上 → 第 2 段会去挂第 1 句的声音，台词全错。
     *
     * 分配策略：目标字数 ∝ 该句秒数；原子（标点句）贪心装入，装不下就进下一段；
     * 末段吞掉所有剩余。原子数少于段数时（文案比配音短）该段为空 → 用上一段末句兜底并告警。
     */
    public List<String> sliceByPlan(String script, List<Integer> secondsPlan) {
        if (script == null || script.isBlank()) return List.of();
        if (secondsPlan == null || secondsPlan.isEmpty()) return slice(script);
        int n = secondsPlan.size();
        if (n == 1) return slice(script, Math.max(5, secondsPlan.get(0)));

        int totalSecs = 0;
        for (Integer s : secondsPlan) totalSecs += Math.max(1, s == null ? 1 : s);
        int len = script.length();

        // 目标字数：按秒数占比分配（末段吃掉余数，保证字数总和 = 全文）
        int[] target = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            if (i == n - 1) {
                target[i] = Math.max(4, len - used);
            } else {
                int t = (int) Math.round((double) len * Math.max(1, secondsPlan.get(i)) / totalSecs);
                int reserve = (n - 1 - i) * 4;              // 给后面每段至少留 4 字
                t = Math.max(4, Math.min(t, len - used - reserve));
                target[i] = t;
                used += t;
            }
        }

        int maxTarget = 4;
        for (int t : target) maxTarget = Math.max(maxTarget, t);
        List<String> atoms = splitIntoAtoms(script, maxTarget);

        List<String> out = new ArrayList<>();
        int ai = 0;
        for (int i = 0; i < n; i++) {
            StringBuilder buf = new StringBuilder();
            while (ai < atoms.size()) {
                if (i == n - 1) {                            // 末段：全部剩余
                    buf.append(atoms.get(ai++));
                    continue;
                }
                String next = atoms.get(ai);
                if (buf.length() > 0 && buf.length() + next.length() > target[i]) break;
                buf.append(atoms.get(ai++));
                if (buf.length() >= target[i]) break;
            }
            String seg = buf.toString().trim();
            if (seg.isEmpty()) {
                // 文案原子已用完（文案比配音短）：重复上一段末句兜底，别让富化拿到空串
                seg = out.isEmpty() ? script.trim() : tailSentenceOf(out.get(out.size() - 1));
                System.out.println("[切片] 警告：第 " + (i + 1) + " 句配音没有对应文案，已用上一段末句兜底；"
                        + "建议补足文案（配音 " + n + " 句）");
            }
            out.add(seg);
        }
        return out;
    }

    /** 取一段文案的最后一句（用于分句兜底，避免整段重复） */
    private String tailSentenceOf(String seg) {
        if (seg == null || seg.isBlank()) return "";
        int cut = -1;
        for (int i = seg.length() - 2; i >= 0; i--) {
            if (PRIMARY_DELIMS.indexOf(seg.charAt(i)) >= 0) { cut = i + 1; break; }
        }
        String tail = cut > 0 ? seg.substring(cut).trim() : seg.trim();
        return tail.isEmpty() ? seg.trim() : tail;
    }

    // ── Step 3: 过短段合并 ───────────────────────────────────

    /**
     * 从头扫描，若某段（非最后一段）长度 < minChars，
     * 则与下一段合并。重复直到所有非末段都 ≥ minChars。
     *
     * 末段特殊处理：如果末段长度 < minChars 且总段数 > 1，
     * 则将末段向上合并到倒数第二段（避免"下期见。嘘。"这种 4 字尾巴单独成一段 30s 视频）。
     */
    private List<String> mergeShortSegments(List<String> segments, int minChars) {
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
                // 最后一段不参与向下合并（没有下一段可合）
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

        // 末段过短保护：向上并入倒数第二段
        if (merged.size() >= 2) {
            String last = merged.get(merged.size() - 1);
            if (last.length() < minChars) {
                String penultimate = merged.get(merged.size() - 2);
                merged.set(merged.size() - 2, penultimate + last);
                merged.remove(merged.size() - 1);
            }
        }

        return merged;
    }
}
