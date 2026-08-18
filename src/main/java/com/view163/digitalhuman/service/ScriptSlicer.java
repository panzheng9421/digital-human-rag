package com.view163.digitalhuman.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage1.5 切片：把完整口播稿按字数切成 N 段，每段 ≤ ~120 字（对应 30s @ 240字/分），
 * 在句号/问号/感叹号/分号/换行边界封口，不切断语义单元。
 * 超长单句（内部无断句标点且 >120 字）整句成一段（边界情况，正常口播稿不会出现）。
 * 切片放在 Stage1（出稿）之后、Stage2（富化）之前，使每段富化后的画面提示天然匹配 30s。
 */
@Component
public class ScriptSlicer {

    private static final int MAX_CHARS = 120;

    public List<String> slice(String script) {
        List<String> result = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return result;
        }
        List<String> sentences = splitSentences(script);
        StringBuilder buf = new StringBuilder();
        for (String s : sentences) {
            if (buf.length() > 0 && buf.length() + s.length() > MAX_CHARS) {
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
