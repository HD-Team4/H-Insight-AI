package com.hinsight.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

/**
 * 임베딩 Lambda(hf4-embedding) invoke용 클라이언트. S3Config와 동일하게 .env의 AWS_* 키 사용.
 * 조직 SCP가 익명 Function URL을 차단하므로 IAM 서명이 자동으로 붙는 SDK invoke로 호출한다.
 */
@Configuration
public class LambdaConfig {

    @Bean
    public LambdaClient lambdaClient(@Value("${AWS_ACCESS_KEY_ID:}") String accessKey,
                                     @Value("${AWS_SECRET_ACCESS_KEY:}") String secretKey,
                                     @Value("${AWS_REGION:ap-northeast-2}") String region) {
        var builder = LambdaClient.builder().region(Region.of(region));
        // .env(AWS_*)에 키가 있으면 그걸 쓰고, 없으면 기본 자격증명 체인으로 폴백
        // (로컬 ~/.aws/credentials, EC2 인스턴스 프로파일 등).
        // placeholder 폴백을 쓰면 로컬처럼 .env에 AWS_* 없는 환경에서 403(security token invalid)으로
        // 챗봇 임베딩이 전부 실패하므로 금지.
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }
}
