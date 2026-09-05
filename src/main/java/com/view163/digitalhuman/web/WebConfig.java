package com.view163.digitalhuman.web;

import com.view163.digitalhuman.config.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 静态资源映射：把本地 output/ 目录暴露为 /output/**，
 * 供主页「在线体验」区直接播放与下载成片（videoUrl = /output/{taskId}/final.mp4）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public WebConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path out = Paths.get(props.getVideo().getOutputDir()).toAbsolutePath().normalize();
        registry.addResourceHandler("/output/**")
                .addResourceLocations("file:" + out.toString().replace('\\', '/') + "/");
    }
}
