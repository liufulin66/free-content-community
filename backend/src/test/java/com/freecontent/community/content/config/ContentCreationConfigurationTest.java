package com.freecontent.community.content.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ContentCreationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ContentCreationConfiguration.class)
            .withPropertyValues("app.content.creation.ip-per-minute-limit=60", "app.content.creation.user-hourly-limit=100", "app.content.creation.redis-key-prefix=test:content:");
    @ParameterizedTest @ValueSource(strings = {"ip-per-minute-limit=0", "user-hourly-limit=0", "user-hourly-limit=-1", "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void rejectsInvalidCreationConfiguration(String value) {
        runner.withPropertyValues("app.content.creation." + value).run(context -> assertThat(context).hasFailed());
    }
}
