package com.freecontent.community.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 统一启用任务调度；各模块继续通过自己的配置控制任务是否执行。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration { }
