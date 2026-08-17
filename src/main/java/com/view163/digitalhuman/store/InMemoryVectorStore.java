package com.view163.digitalhuman.store;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** MVP 用内存向量库；后期可替换为 PGVector / Milvus 实现同一接口 */
@Component
public class InMemoryVectorStore implements VectorStore {

    private final List<ChunkRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void add(ChunkRecord record) {
        records.add(record);
    }

    @Override
    public List<ChunkRecord> getAll() {
        return records;
    }

    @Override
    public int size() {
        return records.size();
    }
}
