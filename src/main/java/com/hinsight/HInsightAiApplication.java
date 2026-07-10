package com.hinsight;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync                              // 리포트 발송(@Async) 백그라운드 처리 활성화
@EnableScheduling                         // 주간 리포트 자동 발송(@Scheduled) 활성화
@MapperScan("com.hinsight.**.dao")
@SpringBootApplication
public class HInsightAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(HInsightAiApplication.class, args);
    }
}
