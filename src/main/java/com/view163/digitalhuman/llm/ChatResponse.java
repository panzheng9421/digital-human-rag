package com.view163.digitalhuman.llm;

import java.util.List;

/** DeepSeek chat/completions 接口返回结构（OpenAI 兼容） */
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
