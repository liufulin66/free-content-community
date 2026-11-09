package com.freecontent.community.tag.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class TagCreationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TagCreationConfiguration.class)
            .withPropertyValues("app.tag.creation.ip-per-minute-limit=60", "app.tag.creation.user-hourly-limit=100",
                    "app.tag.creation.redis-key-prefix=test:tag:");
    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-hourly-limit=0", "user-hourly-limit=-1",
            "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void rejectsInvalidCreationConfiguration(String value) {
        runner.withPropertyValues("app.tag.creation." + value).run(context -> assertThat(context).hasFailed());
    }
}
