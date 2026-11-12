package com.freecontent.community.tag.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.freecontent.community.config.PropertyBindingTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class TagUpdatePropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(TagModuleConfiguration.class)
            .withPropertyValues("app.tag.update.ip-per-minute-limit=60", "app.tag.update.user-hourly-limit=100",
                    "app.tag.update.redis-key-prefix=test:tag-update:");

    @Test
    void updateLimitsAreConfigurableAndIndependent() {
        runner.withPropertyValues("app.tag.update.ip-per-minute-limit=10", "app.tag.update.user-hourly-limit=20")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TagUpdateProperties.class)).isEqualTo(new TagUpdateProperties(10, 20, "test:tag-update:"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-hourly-limit=0", "user-hourly-limit=-1",
            "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void unusableUpdateConfigurationIsRejected(String value) {
        runner.withPropertyValues("app.tag.update." + value).run(context -> assertThat(context).hasFailed());
    }
}
