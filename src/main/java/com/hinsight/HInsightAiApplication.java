package com.hinsight;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.hinsight.**.dao")
@SpringBootApplication
public class HInsightAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HInsightAiApplication.class, args);
    }
}
