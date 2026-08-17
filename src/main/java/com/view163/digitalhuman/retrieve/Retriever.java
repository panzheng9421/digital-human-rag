package com.view163.digitalhuman.retrieve;

import com.view163.digitalhuman.store.ChunkRecord;
import com.view163.digitalhuman.store.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class Retriever {

    /** 余弦相似取 top-k 片段 */
    public List<ChunkRecord> retrieve(VectorStore store, float[] queryVec, int topK) {
        List<Scored> scored = new ArrayList<>();
        for (ChunkRecord r : store.getAll()) {
            scored.add(new Scored(r, cosine(queryVec, r.getVector())));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int k = Math.min(topK, scored.size());
        List<ChunkRecord> result = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            result.add(scored.get(i).record);
        }
        return result;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static class Scored {
        ChunkRecord record;
        double score;

        Scored(ChunkRecord record, double score) {
            this.record = record;
            this.score = score;
        }
    }
}
