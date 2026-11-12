package com.freecontent.community.tag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 模块内的参数统一注册；各业务配置分组与校验保持独立。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ActiveTagQueryProperties.class,
        TagQueryProperties.class,
        TagUpdateProperties.class})
public class TagModuleConfiguration { }
