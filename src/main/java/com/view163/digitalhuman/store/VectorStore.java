package com.view163.digitalhuman.store;

import java.util.List;

public interface VectorStore {
    void add(ChunkRecord record);

    /** 清空全部向量（重建知识库前调用，避免新旧切片累积） */
    void clear();

    List<ChunkRecord> getAll();

    int size();
}
