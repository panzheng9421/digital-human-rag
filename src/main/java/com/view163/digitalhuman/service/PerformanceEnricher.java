package com.view163.digitalhuman.service;

import com.view163.digitalhuman.llm.DeepSeekClient;
import com.view163.digitalhuman.prompt.EnrichPrompt;
import org.springframework.stereotype.Service;

/**
 * Stage2 表演富化：把 RAG 产出的裸口播稿，再调一次 DeepSeek，
 * 结合小胖 Skill 的表演准则，转成 Seedance 视频提示词（画面/节奏/音色）。
 */
@Service
public class PerformanceEnricher {

    private final DeepSeekClient llm;
    private final EnrichPrompt enrichPrompt;

    public PerformanceEnricher(DeepSeekClient llm, EnrichPrompt enrichPrompt) {
        this.llm = llm;
        this.enrichPrompt = enrichPrompt;
    }

    /** 返回完整富化文本（含 [画面提示][台词节奏][音色锚定]）
     * @param persona 英文人设ID（pan/anuo/kaka/samuel），用于选择姿势等差异化模板
     * @param index   第几句台词（1 起始）：多条配音时按该句时长生成时间戳 */
    public String enrich(String script, String personaName, String persona) {
        return enrich(script, personaName, persona, 1);
    }

    public String enrich(String script, String personaName, String persona, int index) {
        String system = enrichPrompt.buildSystem(persona, index);
        String user = enrichPrompt.buildUser(script, personaName);
        System.out.println("[ENRICH] ── 富化入参 ── persona=" + persona + "（" + personaName
                + "），第 " + index + " 段，片段 " + script.length() + " 字，system " + system.length()
                + " 字，user " + user.length() + " 字");
        System.out.println("[ENRICH] user（发给 DeepSeek 的完整富化文本）──\n" + user);
        return llm.chat(system, user);
    }
}
