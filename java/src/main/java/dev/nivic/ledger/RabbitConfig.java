package dev.nivic.ledger;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE_NAME = "gtel-events";
    public static final String QUEUE_NAME = "crypto.deposits";
    public static final String ROUTING_KEY = "ledger.crypto";

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue cryptoDepositQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding binding(Queue cryptoDepositQueue, TopicExchange exchange) {
        return BindingBuilder
            .bind(cryptoDepositQueue)
            .to(exchange)
            .with(ROUTING_KEY);
    }
}
