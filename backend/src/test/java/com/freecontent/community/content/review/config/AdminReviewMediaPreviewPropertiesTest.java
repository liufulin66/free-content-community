package com.freecontent.community.content.review.config;

import com.freecontent.community.content.config.ContentModuleConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.freecontent.community.config.PropertyBindingTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class AdminReviewMediaPreviewPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(ContentModuleConfiguration.class)
            .withPropertyValues("app.content.admin-review-media-preview.read-url-ttl-seconds=300","app.content.admin-review-media-preview.ip-per-minute-limit=120",
                    "app.content.admin-review-media-preview.user-per-minute-limit=60","app.content.admin-review-media-preview.redis-key-prefix=test:preview:");
    @ParameterizedTest @ValueSource(strings = {"read-url-ttl-seconds=0","read-url-ttl-seconds=-1","read-url-ttl-seconds=604801","ip-per-minute-limit=0","user-per-minute-limit=0","redis-key-prefix=","redis-key-prefix=bad{slot}"})
    void invalidSigningAndQuotaSettingsFailAtStartup(String value) {
        runner.withPropertyValues("app.content.admin-review-media-preview."+value).run(context -> assertThat(context).hasFailed());
    }
    @org.junit.jupiter.api.Test
    void documentedDefaultsBindSuccessfully() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(AdminReviewMediaPreviewProperties.class));
    }
}
