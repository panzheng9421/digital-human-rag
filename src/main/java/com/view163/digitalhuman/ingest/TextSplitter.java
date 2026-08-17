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

    /** 单篇文本按长度切片，重叠 overlap 字符避免切断语义 */
    public List<String> split(String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + chunkSize, clean.length());
            chunks.add(clean.substring(start, end));
            if (end == clean.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }

    public List<String> splitAll(List<String> docs) {
        List<String> all = new ArrayList<>();
        for (String d : docs) {
            all.addAll(split(d));
        }
        return all;
    }
}
