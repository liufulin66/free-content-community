package com.freecontent.community.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailChallengeProperties.class)
@EnableScheduling
public class EmailChallengeConfiguration {

    @Bean
    public Clock authenticationClock() {
        return Clock.systemUTC();
    }
}
