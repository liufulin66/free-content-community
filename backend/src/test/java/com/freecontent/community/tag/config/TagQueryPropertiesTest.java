package com.freecontent.community.tag.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.freecontent.community.config.PropertyBindingTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class TagQueryPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(TagModuleConfiguration.class)
            .withPropertyValues("app.tag.query.ip-per-minute-limit=120", "app.tag.query.user-per-minute-limit=60",
                    "app.tag.query.redis-key-prefix=test:tag-query:");

    @Test
    void bindsIndependentQueryLimitsAndAllowsOverrides() {
        runner.withPropertyValues("app.tag.query.ip-per-minute-limit=20", "app.tag.query.user-per-minute-limit=10")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TagQueryProperties.class))
                            .isEqualTo(new TagQueryProperties(20, 10, "test:tag-query:"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-per-minute-limit=0", "user-per-minute-limit=-1",
            "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void rejectsUnusableQueryConfiguration(String value) {
        runner.withPropertyValues("app.tag.query." + value).run(context -> assertThat(context).hasFailed());
    }
}
