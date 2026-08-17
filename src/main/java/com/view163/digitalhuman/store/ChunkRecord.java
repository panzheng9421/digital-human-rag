package com.view163.digitalhuman.store;

public class ChunkRecord {

    private final String id;
    private final String text;
    private final float[] vector;

    public ChunkRecord(String id, String text, float[] vector) {
        this.id = id;
        this.text = text;
        this.vector = vector;
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public float[] getVector() {
        return vector;
    }
}
