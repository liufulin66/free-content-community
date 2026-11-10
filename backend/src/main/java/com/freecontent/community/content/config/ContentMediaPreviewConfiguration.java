package com.freecontent.community.content.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContentMediaPreviewProperties.class)
public class ContentMediaPreviewConfiguration { }
