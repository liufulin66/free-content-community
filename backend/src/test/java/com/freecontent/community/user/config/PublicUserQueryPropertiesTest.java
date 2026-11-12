package com.freecontent.community.user.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicUserQueryPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(UserModuleConfiguration.class)
            .withPropertyValues("app.user.public-query.ip-per-minute-limit=120",
                    "app.user.public-query.user-per-minute-limit=60", "app.user.public-query.redis-key-prefix=test:public-user:");

    @Test
    void validSettingsBindInTheUserModule() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PublicUserQueryProperties.class);
            assertThat(context.getBean(PublicUserQueryProperties.class))
                    .isEqualTo(new PublicUserQueryProperties(120, 60, "test:public-user:"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "ip-per-minute-limit=-1", "user-per-minute-limit=0",
            "user-per-minute-limit=-1", "redis-key-prefix=", "redis-key-prefix=bad{slot}", "redis-key-prefix=bad}"})
    void invalidSettingsStopStartup(String value) {
        runner.withPropertyValues("app.user.public-query." + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PublicUserQueryProperties(120, 60, null));
    }
}
