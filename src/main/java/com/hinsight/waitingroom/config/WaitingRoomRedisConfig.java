package com.hinsight.waitingroom.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 커넥션 이원화.
 *
 * <p>대기열은 전용 ElastiCache 클러스터(hf4-ticketing-queue-redis)를 쓰고,
 * 기존 기능(최근 본 상품·라이브·핫트렌드 등)은 종전 그대로 공용 Redis(spring.data.redis)를 쓴다.
 * 전용 팩토리 빈을 선언하는 순간 스프링부트의 Redis 오토컨픽이 back-off 되므로,
 * 공용 커넥션도 여기서 명시적으로 @Primary 로 재정의해 기존 주입(StringRedisTemplate)을 그대로 유지한다.
 *
 * <p>로컬은 두 호스트 모두 localhost(도커 redis) 기본값이라 한 인스턴스로 동작하고,
 * EC2/운영은 WAITING_ROOM_REDIS_HOST 로 ElastiCache 를 가리킨다(프라이빗 서브넷이라 로컬에선 접근 불가).
 */
@Configuration
public class WaitingRoomRedisConfig {

    public static final String WR_TEMPLATE = "waitingRoomRedisTemplate";

    // ---------- 공용 Redis (기존 기능 유지) : spring.data.redis ----------
    @Bean
    @Primary
    LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port) {
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
    }

    @Bean
    @Primary
    StringRedisTemplate stringRedisTemplate(
            @Qualifier("redisConnectionFactory") RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }

    // ---------- 대기열 전용 Redis : waiting-room.redis ----------
    @Bean
    LettuceConnectionFactory waitingRoomRedisConnectionFactory(
            @Value("${waiting-room.redis.host:localhost}") String host,
            @Value("${waiting-room.redis.port:6379}") int port) {
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
    }

    @Bean(WR_TEMPLATE)
    StringRedisTemplate waitingRoomRedisTemplate(
            @Qualifier("waitingRoomRedisConnectionFactory") RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }
}
