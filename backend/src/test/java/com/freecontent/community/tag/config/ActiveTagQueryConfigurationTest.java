package com.freecontent.community.tag.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ActiveTagQueryConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ActiveTagQueryConfiguration.class)
            .withPropertyValues("app.tag.active-query.ip-per-minute-limit=120", "app.tag.active-query.redis-key-prefix=test:active-tags:");

    @Test
    void bindsAnIndependentConfigurableSourceLimit() {
        runner.withPropertyValues("app.tag.active-query.ip-per-minute-limit=10").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ActiveTagQueryProperties.class)).isEqualTo(new ActiveTagQueryProperties(10, "test:active-tags:"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "ip-per-minute-limit=-1", "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void rejectsUnusableSourceLimitConfiguration(String value) {
        runner.withPropertyValues("app.tag.active-query." + value).run(context -> assertThat(context).hasFailed());
    }
}
