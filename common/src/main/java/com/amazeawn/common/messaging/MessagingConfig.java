package com.amazeawn.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.ArrayList;
import java.util.List;

/**
 * Declares the shared topology (one topic exchange, one command queue, one event queue, each with a
 * dead-letter queue) and the JSON converter both services use. Both services declare everything so
 * startup order between them does not matter.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingConfig {

    @Bean
    public Declarables amazeawnTopology() {
        TopicExchange exchange = new TopicExchange(Routing.EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(Routing.DLX, true, false);

        Queue processorDlq = QueueBuilder.durable(Routing.PROCESSOR_DLQ).build();
        Queue apiDlq = QueueBuilder.durable(Routing.API_DLQ).build();
        Queue processorQueue = QueueBuilder.durable(Routing.PROCESSOR_QUEUE)
                .deadLetterExchange(Routing.DLX)
                .deadLetterRoutingKey(Routing.PROCESSOR_DLQ)
                .build();
        Queue apiQueue = QueueBuilder.durable(Routing.API_QUEUE)
                .deadLetterExchange(Routing.DLX)
                .deadLetterRoutingKey(Routing.API_DLQ)
                .build();

        List<Declarable> declarables = new ArrayList<>(List.of(exchange, dlx, processorQueue, apiQueue, processorDlq, apiDlq,
                BindingBuilder.bind(processorDlq).to(dlx).with(Routing.PROCESSOR_DLQ),
                BindingBuilder.bind(apiDlq).to(dlx).with(Routing.API_DLQ)));
        for (String key : Routing.COMMAND_KEYS) {
            declarables.add(BindingBuilder.bind(processorQueue).to(exchange).with(key));
        }
        for (String key : Routing.EVENT_KEYS) {
            declarables.add(BindingBuilder.bind(apiQueue).to(exchange).with(key));
        }
        return new Declarables(declarables);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.amazeawn.common.messaging");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setExchange(Routing.EXCHANGE);
        template.setMandatory(true);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory,
                                                                               Jackson2JsonMessageConverter converter,
                                                                               MessagingProperties props) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(props.consumerConcurrency());
        factory.setMaxConcurrentConsumers(props.consumerConcurrency());
        factory.setPrefetchCount(props.prefetch());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
