package com.freecontent.community.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 所有模块共用 UTC 时钟；保留旧 Bean 名作为别名。 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {
    @Bean({"applicationClock", "authenticationClock"})
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
