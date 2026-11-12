package com.freecontent.community.content.config;

import com.freecontent.community.content.review.config.AdminReviewMediaPreviewProperties;
import com.freecontent.community.content.review.config.AdminReviewQueryProperties;
import com.freecontent.community.content.review.config.ContentReviewSubmissionProperties;
import com.freecontent.community.content.review.config.ReviewCancellationProperties;
import com.freecontent.community.content.review.config.ReviewDecisionProperties;
import com.freecontent.community.content.workspace.config.ContentDraftSaveProperties;
import com.freecontent.community.content.workspace.config.ContentMediaPreviewProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 模块内的参数统一注册；各业务配置分组与校验保持独立。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AdminReviewMediaPreviewProperties.class,
        AdminReviewQueryProperties.class,
        ContentDraftSaveProperties.class,
        ContentMediaPreviewProperties.class,
        ContentQueryProperties.class,
        ContentReviewSubmissionProperties.class,
        ReviewCancellationProperties.class,
        ReviewDecisionProperties.class})
public class ContentModuleConfiguration { }
