package com.hinsight.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * KAN-106: 마트 읽기용 S3 클라이언트. 읽기전용 IAM 키(.env의 AWS_*)로 mart/* 조회.
 * 대시보드/상품분석 서비스가 이 클라이언트로 S3 마트를 읽고, 실패 시 클래스패스 dummy로 폴백한다.
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${AWS_ACCESS_KEY_ID:}") String accessKey,
                             @Value("${AWS_SECRET_ACCESS_KEY:}") String secretKey,
                             @Value("${AWS_REGION:ap-northeast-2}") String region) {
        // 키가 비어도 빈(placeholder) 자격증명으로 클라이언트를 만들어 앱이 정상 기동하게 한다.
        // (실제 조회 시 인증 실패 → 서비스가 클래스패스 dummy로 폴백)
        if (accessKey.isBlank() || secretKey.isBlank()) {
            accessKey = "placeholder";
            secretKey = "placeholder";
        }
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
