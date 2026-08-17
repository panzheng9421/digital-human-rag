package com.view163.digitalhuman.embed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** 阿里云百炼 OpenAI 兼容 embeddings 接口返回结构 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingResponse {

    public List<Item> data;

    public static class Item {
        public List<Double> embedding;
        public int index;
    }
}
