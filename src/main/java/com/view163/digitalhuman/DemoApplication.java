package com.view163.digitalhuman;

import com.view163.digitalhuman.config.AppProperties;
import com.view163.digitalhuman.service.ScriptGenerator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    CommandLineRunner runner(ScriptGenerator generator, AppProperties props) {
        return args -> {
            String persona = props.getPersona();
            String docsDir = props.getDocsDir() + "/" + persona;
            String topic = args.length > 0 ? String.join(" ", args) : "做一下自我介绍";
            System.out.println("=== 数字分身口播稿生成 ===");
            System.out.println("人设：" + persona + "（知识库：" + docsDir + "）");
            System.out.println("主题：" + topic);
            generator.buildKnowledgeBase(docsDir);
            String script = generator.generate(topic, persona);
            System.out.println("\n--- 生成的口播稿 ---\n" + script);
        };
    }
}
