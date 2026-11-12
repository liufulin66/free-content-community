package com.freecontent.community.content.config;

import com.freecontent.community.content.config.ContentModuleConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.freecontent.community.config.PropertyBindingTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class ContentQueryPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(ContentModuleConfiguration.class)
            .withPropertyValues("app.content.query.ip-per-minute-limit=120", "app.content.query.user-per-minute-limit=60", "app.content.query.redis-key-prefix=test:content:");
    @ParameterizedTest @ValueSource(strings = {"ip-per-minute-limit=0", "user-per-minute-limit=0", "user-per-minute-limit=-1", "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void rejectsInvalidQueryConfiguration(String value) {
        runner.withPropertyValues("app.content.query." + value).run(context -> assertThat(context).hasFailed());
    }
    @org.junit.jupiter.api.Test
    void documentedDefaultsBindSuccessfully() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ContentQueryProperties.class));
    }
}
