package com.freecontent.community.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RegistrationProperties.class)
public class RegistrationConfiguration {
    @Bean
    public PasswordEncoder passwordEncoder(RegistrationProperties properties) {
        PasswordEncoder argon2 = new Argon2PasswordEncoder(16, 32,
                properties.argon2Parallelism(), properties.argon2MemoryKib(),
                properties.argon2Iterations());
        return new DelegatingPasswordEncoder("argon2id", Map.of("argon2id", argon2));
    }
}
