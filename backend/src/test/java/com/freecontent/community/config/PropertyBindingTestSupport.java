package com.freecontent.community.config;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 使用已发布的默认参数进行绑定测试，存储凭据为测试占位值。 */
public final class PropertyBindingTestSupport {
    private PropertyBindingTestSupport() { }

    public static ApplicationContextRunner runner(Class<?>... configurations) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(configurations)
                .withPropertyValues("app.storage.access-key=test-access", "app.storage.secret-key=test-secret");
    }
}
