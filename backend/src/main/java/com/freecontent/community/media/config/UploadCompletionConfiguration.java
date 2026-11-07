package com.freecontent.community.media.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UploadCompletionProperties.class)
public class UploadCompletionConfiguration { }
