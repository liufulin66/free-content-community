package com.freecontent.community.media.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class MediaAssetQueryConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MediaAssetQueryConfiguration.class)
            .withPropertyValues("app.media.asset-query.ip-per-minute-limit=120",
                    "app.media.asset-query.user-per-minute-limit=60", "app.media.asset-query.redis-key-prefix=test:");

    @ParameterizedTest
    @ValueSource(strings = {"ip-per-minute-limit=0", "user-per-minute-limit=0", "user-per-minute-limit=-1",
            "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void rejectsUnusableQueryConfiguration(String value) {
        runner.withPropertyValues("app.media.asset-query." + value).run(context -> assertThat(context).hasFailed());
    }
}
