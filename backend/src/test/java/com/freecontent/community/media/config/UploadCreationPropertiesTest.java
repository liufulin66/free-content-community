package com.freecontent.community.media.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.freecontent.community.config.PropertyBindingTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class UploadCreationPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(MediaModuleConfiguration.class)
            .withPropertyValues("app.media.upload-creation.session-ttl-seconds=7200",
                    "app.media.upload-creation.ip-per-minute-limit=60", "app.media.upload-creation.user-hourly-limit=100",
                    "app.media.upload-creation.redis-key-prefix=test:upload:", "app.storage.endpoint=http://127.0.0.1:9000",
                    "app.storage.access-key=test-access", "app.storage.secret-key=test-secret", "app.storage.bucket=free-content", "app.storage.region=us-east-1");

    @ParameterizedTest
    @ValueSource(strings = {"app.media.upload-creation.session-ttl-seconds=0", "app.media.upload-creation.ip-per-minute-limit=0",
            "app.media.upload-creation.user-hourly-limit=-1", "app.media.upload-creation.redis-key-prefix=bad{slot}",
            "app.storage.endpoint=ftp://example.invalid", "app.storage.endpoint=http://example.invalid/path?secret=value",
            "app.storage.access-key=", "app.storage.secret-key=", "app.storage.bucket=../invalid", "app.storage.region="})
    void rejectsUnusableConfigurationAtStartup(String value) {
        runner.withPropertyValues(value).run(context -> assertThat(context).hasFailed());
    }
    @org.junit.jupiter.api.Test
    void documentedDefaultsBindSuccessfully() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(UploadCreationProperties.class));
    }
}
