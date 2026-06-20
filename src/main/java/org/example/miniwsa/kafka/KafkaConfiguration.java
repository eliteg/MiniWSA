package org.example.miniwsa.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Profile("async")
class KafkaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfiguration.class);

    @Value("${wsa.kafka.topic.ingest}")
    private String ingestTopic;

    @Value("${wsa.kafka.topic.dlq}")
    private String dlqTopic;

    @Bean
    NewTopic ingestTopic() {
        return new NewTopic(ingestTopic, 1, (short) 1);
    }

    @Bean
    NewTopic dlqTopic() {
        return new NewTopic(dlqTopic, 1, (short) 1);
    }

    /** Routes failed messages to the DLQ after 2 retries (1 s apart). */
    @Bean
    CommonErrorHandler errorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> {
                    log.error("DLQ: event key={} offset={} partition={} error={}",
                            record.key(), record.offset(), record.partition(), ex.getMessage());
                    return new TopicPartition(dlqTopic, 0);
                });
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2));
    }
}
