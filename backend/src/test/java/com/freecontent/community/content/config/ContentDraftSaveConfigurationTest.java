package com.freecontent.community.content.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class ContentDraftSaveConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ContentDraftSaveConfiguration.class)
            .withPropertyValues("app.content.draft-save.ip-per-minute-limit=120","app.content.draft-save.user-per-minute-limit=60","app.content.draft-save.redis-key-prefix=test:content:");
    @ParameterizedTest @ValueSource(strings = {"ip-per-minute-limit=0","user-per-minute-limit=0","user-per-minute-limit=-1","redis-key-prefix=","redis-key-prefix=bad{slot}"})
    void rejectsInvalidDraftSaveConfiguration(String value) {
        runner.withPropertyValues("app.content.draft-save."+value).run(context -> assertThat(context).hasFailed());
    }
}
