package com.view163.digitalhuman.service;

import com.view163.digitalhuman.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage1.5 切片：把完整口播稿切成 N 段，每段字数 ≤ seconds × CHARS_PER_SECOND，
 * 使每段天然匹配对应秒数的视频片段（切片粒度与 Video.seconds 自动联动）。
 * 切片放在 Stage1（出稿）之后、Stage2（富化）之前。
 */
@Component
public class ScriptSlicer {

    private static final int CHARS_PER_SECOND = 4;  // 日常口播语速 ~240 字/分

    private final int maxChars;

    public ScriptSlicer(AppProperties props) {
        int seconds = props.getVideo().getSeconds();
        // seconds 异常（≤0）时兜底 120 字（≈30s），避免除零/负数导致整稿变一段
        this.maxChars = seconds > 0 ? seconds * CHARS_PER_SECOND : 120;
    }

    public List<String> slice(String script) {
        List<String> result = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return result;
        }
        List<String> sentences = splitSentences(script);
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() > 0 && buf.length() + s.length() > maxChars) {
                result.add(buf.toString().trim());
                buf.setLength(0);
            }
            buf.append(s);
        }
        if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }
        return result;
    }

    /** 按断句标点切句，保留标点本身 */
    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
