package com.freecontent.community.content.browse.config;
import com.freecontent.community.config.PropertyBindingTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
class PublicContentCursorPropertiesTest {
    private final org.springframework.boot.test.context.runner.ApplicationContextRunner runner=PropertyBindingTestSupport.runner(PublicContentConfiguration.class)
        .withPropertyValues("app.content.public-cursor.ttl-seconds=900","app.content.public-cursor.key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    @Test void validCursorConfigurationBinds(){runner.run(context->assertThat(context).hasNotFailed().hasSingleBean(PublicContentCursorProperties.class));}
    @ParameterizedTest @ValueSource(strings={"ttl-seconds=0","ttl-seconds=86401","key-base64=","key-base64=bad","key-base64=%%%"})
    void invalidLifetimeOrKeyStopsStartup(String value){runner.withPropertyValues("app.content.public-cursor."+value).run(context->assertThat(context).hasFailed());}
}
