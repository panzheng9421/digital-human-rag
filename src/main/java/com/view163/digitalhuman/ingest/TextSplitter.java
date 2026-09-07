package com.view163.digitalhuman.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TextSplitter {

    private final int chunkSize;
    private final int overlap;

    public TextSplitter() {
        this(500, 50);
    }

    public TextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /** 单篇文本按长度切片，重叠 overlap 字符；切点回退到最近的句末标点，避免把句子拦腰斩断 */
    public List<String> split(String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + chunkSize, clean.length());
            if (end < clean.length()) {
                // 在 (start, end] 内从后往前找句末标点，最多回退半个 chunk；找不到就按原长度硬切（兜底）
                int cut = -1;
                for (int i = end; i > start + chunkSize / 2; i--) {
                    if (isSentenceEnd(clean.charAt(i - 1))) {
                        cut = i;
                        break;
                    }
                }
                if (cut > start) {
                    end = cut;
                }
            }
            // 片段开头对齐句首：start 若落在半句上，向后吞掉残句，从下一个句末标点之后开始
            // （最多吞半个 chunk；残句内容已被上一个片段的尾部覆盖，不丢信息）
            int s = start;
            if (s > 0) {
                int head = -1;
                for (int i = s; i < s + (end - s) / 2; i++) {
                    if (isSentenceEnd(clean.charAt(i))) {
                        head = i + 1;
                        break;
                    }
                }
                if (head > s) {
                    s = head;
                }
            }
            chunks.add(clean.substring(s, end));
            if (end >= clean.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);   // 保证 start 前进，避免死循环
        }
        return chunks;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；'
                || c == '!' || c == '?' || c == ';';
    }

    public List<String> splitAll(List<String> docs) {
        List<String> all = new ArrayList<>();
        for (String d : docs) {
            all.addAll(split(d));
        }
        return all;
    }
}
