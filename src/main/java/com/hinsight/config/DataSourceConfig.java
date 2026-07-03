package com.hinsight.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 데이터소스 설정 (멀티 데이터소스).
 * - MySQL 을 @Primary 로 명시(MyBatis 가 사용). DataSourceProperties.initializeDataSourceBuilder()
 *   를 써서 auto-config 와 동일하게 url→jdbcUrl 매핑을 처리한다.
 * - 벡터DB(Postgres/pgvector)는 두 번째 DataSource + 벡터검색 전용 JdbcTemplate.
 * (DataSource 빈을 직접 정의하면 단일 DataSource 자동구성이 물러나므로 둘 다 명시)
 */
@Configuration
public class DataSourceConfig {

    // === MySQL (기본) ===
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    // === 벡터DB (Postgres/pgvector) ===
    @Bean
    @ConfigurationProperties("spring.vector-datasource")
    public DataSourceProperties vectorDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource vectorDataSource(
            @Qualifier("vectorDataSourceProperties") DataSourceProperties vectorDataSourceProperties) {
        return vectorDataSourceProperties.initializeDataSourceBuilder().build();
    }

    // 벡터검색 전용 JdbcTemplate. 주입 시 @Qualifier("vectorJdbcTemplate") 로 구분.
    @Bean
    public JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource vectorDataSource) {
        return new JdbcTemplate(vectorDataSource);
    }
}
