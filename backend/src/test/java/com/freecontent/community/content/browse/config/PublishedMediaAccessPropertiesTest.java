package com.freecontent.community.content.browse.config;

import com.freecontent.community.config.PropertyBindingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublishedMediaAccessPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(PublicContentConfiguration.class)
            .withPropertyValues("app.content.public-cursor.key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                    "app.content.public-media-access.read-url-ttl-seconds=300", "app.content.public-media-access.ip-per-minute-limit=120",
                    "app.content.public-media-access.user-per-minute-limit=60", "app.content.public-media-access.redis-key-prefix=test:sign:");

    @Test
    void validSettingsBind() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PublishedMediaAccessProperties.class);
            assertThat(context.getBean(PublishedMediaAccessProperties.class))
                    .isEqualTo(new PublishedMediaAccessProperties(300, 120, 60, "test:sign:"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"read-url-ttl-seconds=0", "read-url-ttl-seconds=604801", "ip-per-minute-limit=0",
            "user-per-minute-limit=0", "redis-key-prefix=", "redis-key-prefix=bad{slot}", "redis-key-prefix=bad}"})
    void invalidSettingsStopStartup(String value) {
        runner.withPropertyValues("app.content.public-media-access." + value)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aNullPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PublishedMediaAccessProperties(300, 120, 60, null));
    }
}
