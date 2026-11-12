package com.freecontent.community.config;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class TimeConfigurationTest {
    @Test
    void applicationClockIsAvailableWithoutAuthenticationConfiguration() {
        new ApplicationContextRunner().withUserConfiguration(TimeConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(Clock.class);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        });
    }
}
