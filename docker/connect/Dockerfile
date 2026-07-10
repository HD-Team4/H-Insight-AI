# Kafka Connect 런타임 + Aiven S3 Sink 커넥터 (Apache-2.0)
# (베이스는 debezium 프로젝트가 배포하는 Kafka Connect 배포판일 뿐, CDC/소스 커넥터는 쓰지 않음)
# 1단계: 커넥터 다운로드/해제 (alpine에 curl/tar 있음)
FROM alpine:3.20 AS downloader
ARG AIVEN_S3_VERSION=2.15.0
RUN apk add --no-cache curl tar && \
    curl -sSL -o /tmp/s3.tar \
      "https://github.com/Aiven-Open/s3-connector-for-apache-kafka/releases/download/v${AIVEN_S3_VERSION}/s3-connector-for-apache-kafka-${AIVEN_S3_VERSION}.tar" && \
    mkdir -p /plugins && \
    tar -xf /tmp/s3.tar -C /plugins

# 2단계: Kafka Connect 이미지에 S3 Sink 플러그인 복사
FROM quay.io/debezium/connect:3.0
COPY --from=downloader /plugins/ /kafka/connect/
