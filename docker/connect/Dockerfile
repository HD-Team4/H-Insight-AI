# Debezium Connect + Aiven S3 Sink 커넥터 (Apache-2.0)
# 1단계: 커넥터 다운로드/해제 (alpine에 curl/tar 있음)
FROM alpine:3.20 AS downloader
ARG AIVEN_S3_VERSION=2.15.0
RUN apk add --no-cache curl tar && \
    curl -sSL -o /tmp/s3.tar \
      "https://github.com/Aiven-Open/s3-connector-for-apache-kafka/releases/download/v${AIVEN_S3_VERSION}/s3-connector-for-apache-kafka-${AIVEN_S3_VERSION}.tar" && \
    mkdir -p /plugins && \
    tar -xf /tmp/s3.tar -C /plugins

# 2단계: Debezium Connect 이미지에 커넥터 플러그인 복사
FROM quay.io/debezium/connect:3.0
COPY --from=downloader /plugins/ /kafka/connect/
