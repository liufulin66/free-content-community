package com.freecontent.community.media.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.freecontent.community.config.PropertyBindingTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class UploadQueryPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(MediaModuleConfiguration.class)
            .withPropertyValues("app.media.upload-query.ip-per-minute-limit=120",
                    "app.media.upload-query.user-per-minute-limit=60", "app.media.upload-query.redis-key-prefix=test:media:");

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-per-minute-limit=-1", "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void rejectsInvalidQuotaConfigurationAtStartup(String value) {
        runner.withPropertyValues("app.media.upload-query." + value).run(context -> assertThat(context).hasFailed());
    }
    @org.junit.jupiter.api.Test
    void documentedDefaultsBindSuccessfully() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(UploadQueryProperties.class));
    }
}
