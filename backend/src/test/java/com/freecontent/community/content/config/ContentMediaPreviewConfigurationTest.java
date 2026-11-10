package com.freecontent.community.content.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ContentMediaPreviewConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ContentMediaPreviewConfiguration.class)
            .withPropertyValues("app.content.media-preview.read-url-ttl-seconds=300","app.content.media-preview.ip-per-minute-limit=120",
                    "app.content.media-preview.user-per-minute-limit=60","app.content.media-preview.redis-key-prefix=test:preview:");
    @ParameterizedTest @ValueSource(strings = {"read-url-ttl-seconds=0","read-url-ttl-seconds=-1","read-url-ttl-seconds=604801","ip-per-minute-limit=0","user-per-minute-limit=0","redis-key-prefix=","redis-key-prefix=bad{slot}"})
    void invalidSigningAndQuotaSettingsFailAtStartup(String value) {
        runner.withPropertyValues("app.content.media-preview."+value).run(context -> assertThat(context).hasFailed());
    }
}
