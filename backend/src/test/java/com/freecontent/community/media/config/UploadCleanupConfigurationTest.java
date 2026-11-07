package com.freecontent.community.media.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class UploadCleanupConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(UploadCancellationConfiguration.class)
            .withPropertyValues("app.media.upload-cancel.ip-per-minute-limit=120", "app.media.upload-cancel.user-per-minute-limit=60",
                    "app.media.upload-cancel.redis-key-prefix=test:cancel:", "app.media.upload-cleanup.enabled=false",
                    "app.media.upload-cleanup.grace-seconds=600", "app.media.upload-cleanup.poll-delay-millis=1000",
                    "app.media.upload-cleanup.lease-seconds=60", "app.media.upload-cleanup.max-attempts=3", "app.media.upload-cleanup.retry-base-seconds=5");
    @ParameterizedTest
    @ValueSource(strings = {"app.media.upload-cancel.ip-per-minute-limit=0", "app.media.upload-cancel.user-per-minute-limit=0",
            "app.media.upload-cancel.redis-key-prefix=bad{slot}", "app.media.upload-cancel.redis-key-prefix=",
            "app.media.upload-cleanup.grace-seconds=-1", "app.media.upload-cleanup.poll-delay-millis=0",
            "app.media.upload-cleanup.lease-seconds=10", "app.media.upload-cleanup.max-attempts=0", "app.media.upload-cleanup.retry-base-seconds=0"})
    void rejectsInvalidConfigurationAtStartup(String value) {
        runner.withPropertyValues(value).run(context -> assertThat(context).hasFailed());
    }
}
