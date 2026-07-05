# VOD Live AWS 확장

## DB에 영상 파일을 저장하지 않는 이유

MP4 같은 대용량 바이너리를 DB에 저장하면 DB 용량, 백업 시간, 네트워크 부하, 응답 성능에 부담이 커진다. DB에는 `video_url`, 제목, 문구, 썸네일, `cache_version` 같은 메타데이터만 저장하고, 영상 파일은 객체 스토리지와 CDN에서 제공하는 구조가 운영에 적합하다.

## S3와 CloudFront 구성

S3 버킷에 MP4 5개를 업로드한다.

```text
live/windbreaker.mp4
live/onepiece.mp4
live/sneakers.mp4
live/cardigan.mp4
live/bag.mp4
```

CloudFront 배포를 생성하고 S3를 원본으로 연결한다.

DB의 `live_session.video_url`을 CloudFront URL로 변경한다.

```text
https://dxxxxx.cloudfront.net/live/windbreaker.mp4
https://dxxxxx.cloudfront.net/live/onepiece.mp4
https://dxxxxx.cloudfront.net/live/sneakers.mp4
https://dxxxxx.cloudfront.net/live/cardigan.mp4
https://dxxxxx.cloudfront.net/live/bag.mp4
```

Spring Boot와 `room.html` 로직은 그대로 사용 가능하다. 현재 MVP는 DB에 저장된 전체 `video_url`을 그대로 `<video>` 소스로 사용한다.

## 접근 제어

S3 public access를 열어 MP4를 직접 접근하게 하는 방식은 빠른 테스트용으로만 사용한다.

발표용 또는 현업형 구조에서는 CloudFront OAC(Origin Access Control)를 사용해 S3 직접 접근을 막고, 사용자는 CloudFront URL로만 영상을 보게 하는 구성을 권장한다.

## 캐시 갱신

영상 파일을 교체했지만 URL 경로가 같다면 `cache_version` 값을 올린다.

```text
video_url = https://dxxxxx.cloudfront.net/live/windbreaker.mp4
cache_version = 2
```

화면에서는 다음처럼 요청되므로 브라우저와 CDN 캐시를 우회할 수 있다.

```text
https://dxxxxx.cloudfront.net/live/windbreaker.mp4?v=2
```

## 추후 확장

- S3 업로드 이벤트로 영상 처리 파이프라인 시작
- Lambda로 메타데이터 생성 또는 상태 업데이트
- MediaConvert로 MP4를 HLS로 변환
- HLS 변환 결과를 CloudFront로 제공
- CloudFront 캐싱 정책과 무효화 전략 정리
