package com.example.langchain4j.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * 业务表 DDL 初始化：后台线程容错执行 schema.sql。
 * <p>
 * 不使用 spring.sql.init（其连接失败会阻断启动）；本初始化器：
 * <ul>
 *   <li>后台执行，不阻塞启动流程；</li>
 *   <li>PG 不可达时仅告警（DDL 幂等，可在 DB 恢复后重启补建）；</li>
 *   <li>checkpoint 三表由 PostgresSaver 自行建表，与此解耦。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class SchemaInitializer {

    @Bean
    public ApplicationRunner schemaInitializerRunner(DataSource dataSource) {
        return (ApplicationArguments args) -> {
            Thread thread = new Thread(() -> initSchema(dataSource), "schema-init");
            thread.setDaemon(true);
            thread.start();
        };
    }

    private void initSchema(DataSource dataSource) {
        try {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("db/schema.sql"));
            populator.setContinueOnError(true);
            populator.execute(dataSource);
            log.info("[schema] business tables initialized");
        } catch (Exception e) {
            // PG 不可达：降级运行（业务表缺省时由全局异常处理兜底）
            log.warn("[schema] schema init skipped (db unavailable?): {}", e.getMessage());
        }
    }
}
