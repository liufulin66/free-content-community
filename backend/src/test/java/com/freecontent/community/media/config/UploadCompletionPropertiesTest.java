package com.freecontent.community.media.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.freecontent.community.config.PropertyBindingTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class UploadCompletionPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(MediaModuleConfiguration.class)
            .withPropertyValues("app.media.upload-completion.ip-per-minute-limit=120",
                    "app.media.upload-completion.user-per-minute-limit=60", "app.media.upload-completion.redis-key-prefix=test:",
                    "app.media.upload-completion.retry-after-seconds=2", "app.media.task-query.ip-per-minute-limit=120",
                    "app.media.task-query.user-per-minute-limit=60", "app.media.task-query.redis-key-prefix=test:");

    @ParameterizedTest
    @ValueSource(strings = {"upload-completion.ip-per-minute-limit=0", "upload-completion.user-per-minute-limit=-1",
            "upload-completion.redis-key-prefix=", "upload-completion.redis-key-prefix=bad{slot}",
            "upload-completion.retry-after-seconds=0", "task-query.ip-per-minute-limit=0",
            "task-query.user-per-minute-limit=0", "task-query.redis-key-prefix=", "task-query.redis-key-prefix=bad{slot}"})
    void rejectsUnusableQuotaAndPollingConfiguration(String override) {
        runner.withPropertyValues("app.media." + override).run(context -> assertThat(context).hasFailed());
    }
    @org.junit.jupiter.api.Test
    void documentedDefaultsBindSuccessfully() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(MediaTaskQueryProperties.class));
    }
}
