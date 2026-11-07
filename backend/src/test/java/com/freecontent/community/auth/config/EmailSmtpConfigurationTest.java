package com.freecontent.community.auth.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSmtpConfigurationTest {
    @ParameterizedTest
    @ValueSource(strings = {"connectiontimeout", "timeout", "writetimeout"})
    void effectiveSenderBoundsEachSocketWaitBelowTheTaskLease(String timeout) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .run(context -> {
                    String value = context.getBean(JavaMailSenderImpl.class).getJavaMailProperties()
                            .getProperty("mail.smtp." + timeout);
                    assertThat(value).as("SMTP %s必须配置有限超时", timeout).isNotNull();
                    assertThat(Long.parseLong(value)).isPositive()
                            .isLessThan(context.getEnvironment()
                                    .getRequiredProperty("app.auth.email-challenge.lease-seconds", Long.class) * 1000);
                });
    }
}
