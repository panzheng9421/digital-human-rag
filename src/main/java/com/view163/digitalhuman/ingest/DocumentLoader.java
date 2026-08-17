package com.view163.digitalhuman.ingest;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
public class DocumentLoader {

    /** 读取目录下所有 .md / .txt，返回每篇完整文本 */
    public List<String> loadAll(String dir) throws IOException {
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> docs = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".txt");
                    })
                    .forEach(p -> {
                        try {
                            docs.add(Files.readString(p));
                        } catch (IOException ignored) {
                            // 单文件读取失败跳过
                        }
                    });
        }
        return docs;
    }
}
