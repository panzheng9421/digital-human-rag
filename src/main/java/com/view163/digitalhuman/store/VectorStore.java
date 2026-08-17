package com.view163.digitalhuman.store;

import java.util.List;

public interface VectorStore {
    void add(ChunkRecord record);

    List<ChunkRecord> getAll();

    int size();
}
