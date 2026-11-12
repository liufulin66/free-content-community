package com.freecontent.community.content.browse.config;
import com.freecontent.community.config.PropertyBindingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
class PublicContentQueryPropertiesTest {
    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner=PropertyBindingTestSupport.runner(PublicContentConfiguration.class)
        .withPropertyValues("app.content.public-cursor.key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.content.public-query.ip-per-minute-limit=120","app.content.public-query.user-per-minute-limit=60","app.content.public-query.redis-key-prefix=test:public:");
    @Test void validParametersBind(){runner.run(context->assertThat(context).hasNotFailed().hasSingleBean(PublicContentQueryProperties.class));}
    @ParameterizedTest @ValueSource(strings={"ip-per-minute-limit=0","user-per-minute-limit=0","redis-key-prefix=","redis-key-prefix=bad{slot}"})
    void invalidParametersStopStartup(String value){runner.withPropertyValues("app.content.public-query."+value).run(context->assertThat(context).hasFailed());}
}
