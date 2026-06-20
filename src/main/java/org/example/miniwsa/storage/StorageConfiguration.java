package org.example.miniwsa.storage;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration(after = DataSourceAutoConfiguration.class)
class StorageConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    EventStore eventStore(JdbcTemplate jdbc) {
        return new JdbcEventStore(jdbc);
    }
}
