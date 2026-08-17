package com.view163.digitalhuman.service;

import com.view163.digitalhuman.embed.EmbeddingClient;
import com.view163.digitalhuman.ingest.DocumentLoader;
import com.view163.digitalhuman.ingest.TextSplitter;
import com.view163.digitalhuman.llm.DeepSeekClient;
import com.view163.digitalhuman.prompt.PromptBuilder;
import com.view163.digitalhuman.retrieve.Retriever;
import com.view163.digitalhuman.store.ChunkRecord;
import com.view163.digitalhuman.store.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScriptGenerator {

    private final DocumentLoader loader;
    private final TextSplitter splitter;
    private final EmbeddingClient embedder;
    private final VectorStore store;
    private final Retriever retriever;
    private final PromptBuilder promptBuilder;
    private final DeepSeekClient llm;
    private final int topK;

    public ScriptGenerator(DocumentLoader loader,
                           TextSplitter splitter,
                           EmbeddingClient embedder,
                           VectorStore store,
                           Retriever retriever,
                           PromptBuilder promptBuilder,
                           DeepSeekClient llm,
                           @Value("${app.top-k:3}") int topK) {
        this.loader = loader;
        this.splitter = splitter;
        this.embedder = embedder;
        this.store = store;
        this.retriever = retriever;
        this.promptBuilder = promptBuilder;
        this.llm = llm;
        this.topK = topK;
    }

    /** 构建知识库：读文档 -> 切片 -> 向量化 -> 入库 */
    public void buildKnowledgeBase(String docsDir) throws Exception {
        List<String> docs = loader.loadAll(docsDir);
        List<String> chunks = splitter.splitAll(docs);
        System.out.println("[RAG] 载入文档 " + docs.size() + " 篇，切片 " + chunks.size() + " 段");
        int i = 0;
        for (String c : chunks) {
            float[] v = embedder.embed(c);
            store.add(new ChunkRecord("c" + i++, c, v));
        }
        System.out.println("[RAG] 向量库构建完成，共 " + store.size() + " 条");
    }

    /** 给定主题，检索上下文并生成口播稿 */
    public String generate(String topic) {
        float[] qv = embedder.embed(topic);
        List<ChunkRecord> ctx = retriever.retrieve(store, qv, topK);
        String user = promptBuilder.buildUser(topic, ctx);
        return llm.chat(promptBuilder.buildSystem(), user);
    }
}
