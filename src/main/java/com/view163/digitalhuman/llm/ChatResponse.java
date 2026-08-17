package com.view163.digitalhuman.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** DeepSeek chat/completions 接口返回结构（OpenAI 兼容） */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    public List<Choice> choices;

    public static class Choice {
        public Message message;
    }

    public static class Message {
        public String role;
        public String content;
    }
}
