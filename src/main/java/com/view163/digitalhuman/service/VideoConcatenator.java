package com.view163.digitalhuman.service;

import com.view163.digitalhuman.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage4 拼接：用系统 ffmpeg 把多段 30s mp4 拼成一个完整长视频。
 * 优先 -c copy（Seedance 各段参数一致可直接流拷贝拼接），失败则降重编码 -c:v libx264 -c:a aac。
 * 运行前需保证环境已安装 ffmpeg（mac: brew install ffmpeg；win: 官网下载；linux: apt install ffmpeg），
 * 或通过 app.video.ffmpeg-path 指定绝对路径。
 */
@Component
public class VideoConcatenator {

    private final String ffmpegPath;

    public VideoConcatenator(AppProperties props) {
        String p = props.getVideo().getFfmpegPath();
        this.ffmpegPath = (p == null || p.isBlank()) ? "ffmpeg" : p;
    }

    /** 拼接多段视频，返回最终 mp4 绝对路径 */
    public String concat(List<String> segments) throws Exception {
        if (segments == null || segments.size() < 2) {
            throw new IllegalArgumentException("拼接至少需要 2 段视频");
        }
        // 探活 ffmpeg
        Process probe = new ProcessBuilder(ffmpegPath, "-version").redirectErrorStream(true).start();
        if (probe.waitFor() != 0) {
            throw new RuntimeException("未找到 ffmpeg（路径=" + ffmpegPath + "），请安装 ffmpeg 或在配置中指定 app.video.ffmpeg-path");
        }

        Path listFile = Paths.get("concat_list.txt");
        try (BufferedWriter w = Files.newBufferedWriter(listFile, StandardCharsets.UTF_8)) {
            for (String seg : segments) {
                String abs = Paths.get(seg).toAbsolutePath().toString().replace("'", "'\\''");
                w.write("file '" + abs + "'");
                w.newLine();
            }
        }

        String output = "final_" + System.currentTimeMillis() + ".mp4";
        int code = runFfmpeg(listFile, output, true);
        if (code != 0) {
            System.out.println("[拼接] stream copy 失败，改重编码...");
            code = runFfmpeg(listFile, output, false);
        }
        Files.deleteIfExists(listFile);
        if (code != 0) {
            throw new RuntimeException("ffmpeg 拼接失败，退出码=" + code);
        }
        return Paths.get(output).toAbsolutePath().toString();
    }

    private int runFfmpeg(Path listFile, String output, boolean copy) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-f");
        cmd.add("concat");
        cmd.add("-safe");
        cmd.add("0");
        cmd.add("-i");
        cmd.add(listFile.toAbsolutePath().toString());
        if (copy) {
            cmd.add("-c");
            cmd.add("copy");
        } else {
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-c:a");
            cmd.add("aac");
        }
        cmd.add("-y");
        cmd.add(output);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String low = line.toLowerCase();
                if (low.contains("error") || low.contains("fail")) {
                    System.out.println("[ffmpeg] " + line);
                }
            }
        }
        return p.waitFor();
    }
}
