# VOD Live 로컬 테스트

## 1. MP4 파일 배치

로컬 테스트용 MP4 5개를 아래 경로에 넣는다.

```text
src/main/resources/static/videos/live/windbreaker.mp4
src/main/resources/static/videos/live/onepiece.mp4
src/main/resources/static/videos/live/sneakers.mp4
src/main/resources/static/videos/live/cardigan.mp4
src/main/resources/static/videos/live/bag.mp4
```

Spring Boot 정적 리소스 경로 기준으로 브라우저에서는 다음 URL로 접근된다.

```text
/videos/live/windbreaker.mp4
/videos/live/onepiece.mp4
/videos/live/sneakers.mp4
/videos/live/cardigan.mp4
/videos/live/bag.mp4
```

## 2. ALTER TABLE SQL 실행

MySQL에 아래 SQL을 실행한다.

```text
docs/sql/live_session_vod_live_migration.sql
```

추가되는 컬럼:

```text
live_title
video_url
thumbnail_url
live_message
cache_version
```

## 3. 더미 라이브방 데이터 반영

아래 SQL을 실행한다.

```text
docs/sql/live_session_vod_live_dummy_data.sql
```

현재 더미 SQL은 상품 1~5 기준으로 작성되어 있다. 기존 `product_id` 라이브방이 있으면 `UPDATE`하고, 없으면 `INSERT`한다. 다른 DB를 사용한다면 각 `product_id`를 실제 존재하는 상품 ID로 변경한다.

## 4. 라이브 세션 API 확인

서버 실행 후 다음 API를 호출한다.

```text
GET /api/live/sessions
```

응답에서 아래 값이 내려오는지 확인한다.

```text
liveTitle
videoUrl
thumbnailUrl
liveMessage
cacheVersion
status
startedAt
```

## 5. 라이브방 화면 확인

각 라이브 세션 ID로 접속한다.

```text
/live/{liveSessionId}
```

확인 항목:

- 라이브방마다 다른 영상이 재생되는지 확인
- 라이브방마다 다른 제목과 문구가 표시되는지 확인
- `product.productName` 상품명 표시가 유지되는지 확인
- 방송 상태 라벨이 유지되는지 확인
- 채팅 UI가 기존처럼 표시되는지 확인

## 6. Redis viewer count 확인

페이지 진입 시 다음 요청이 발생하고 Redis viewer count가 증가하는지 확인한다.

```text
POST /live/{liveSessionId}/viewers/enter
```

페이지에 머무는 동안 다음 요청이 주기적으로 발생하는지 확인한다.

```text
GET /live/{liveSessionId}/status
```

페이지 이탈 시 다음 leave 요청이 발생하는지 브라우저 Network 탭 또는 Redis 값으로 확인한다.

```text
POST /live/{liveSessionId}/viewers/leave
```
