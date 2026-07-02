ARG ES_VERSION=8.15.3
FROM docker.elastic.co/elasticsearch/elasticsearch:${ES_VERSION}

# 한글 형태소 분석기(Nori) 플러그인 설치
RUN bin/elasticsearch-plugin install --batch analysis-nori
