package com.freecontent.community.tag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TagUpdateProperties.class)
public class TagUpdateConfiguration { }
