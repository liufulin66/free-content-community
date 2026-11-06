package com.freecontent.community.user.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileUpdateProperties.class)
public class ProfileUpdateConfiguration { }
