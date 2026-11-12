package com.freecontent.community.content.review.config;

import com.freecontent.community.content.config.ContentModuleConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.freecontent.community.config.PropertyBindingTestSupport;
import static org.assertj.core.api.Assertions.assertThat;

class ContentReviewSubmissionPropertiesTest {
    private final ApplicationContextRunner runner = PropertyBindingTestSupport.runner(ContentModuleConfiguration.class)
            .withPropertyValues("app.content.review-submission.ip-per-minute-limit=60", "app.content.review-submission.user-hourly-limit=100", "app.content.review-submission.redis-key-prefix=test:content:");
    @ParameterizedTest @ValueSource(strings = {"ip-per-minute-limit=0", "user-hourly-limit=0", "user-hourly-limit=-1", "redis-key-prefix=", "redis-key-prefix=bad{slot}"})
    void rejectsInvalidPropertyValues(String value) {
        runner.withPropertyValues("app.content.review-submission." + value).run(context -> assertThat(context).hasFailed());
    }
    @org.junit.jupiter.api.Test
    void documentedDefaultsBindSuccessfully() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(ContentReviewSubmissionProperties.class));
    }
}
