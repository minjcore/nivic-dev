package dev.nivic.gateway.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Exchange
    @Bean
    public TopicExchange gtelEventsExchange() {
        return new TopicExchange("gtel-events", true, false);
    }

    // Queues
    @Bean
    public Queue javaLedgerEventsQueue() {
        return QueueBuilder.durable("java-ledger-events")
            .ttl(86400000)  // 24 hours TTL
            .maxLength(1000000)  // Max 1M messages
            .build();
    }

    @Bean
    public Queue ledgerEventsRetryQueue() {
        return QueueBuilder.durable("ledger-events-retry")
            .ttl(300000)  // 5 minutes TTL
            .maxLength(100000)
            .deadLetterExchange("ledger-events-dlx")
            .build();
    }

    @Bean
    public Queue ledgerEventsDlqQueue() {
        return QueueBuilder.durable("ledger-events-dlq")
            .build();
    }

    // Bindings
    @Bean
    public Binding javaLedgerEventsBinding(Queue javaLedgerEventsQueue, TopicExchange gtelEventsExchange) {
        return BindingBuilder.bind(javaLedgerEventsQueue)
            .to(gtelEventsExchange)
            .with("ledger.*");
    }

    @Bean
    public Binding ledgerEventsRetryBinding(Queue ledgerEventsRetryQueue, TopicExchange gtelEventsExchange) {
        return BindingBuilder.bind(ledgerEventsRetryQueue)
            .to(gtelEventsExchange)
            .with("ledger.*");
    }

    // RabbitTemplate (for publishing)
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("Message not confirmed: {}", cause);
            }
        });
        return template;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RabbitMQConfig.class);
}
