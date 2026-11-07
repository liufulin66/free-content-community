package com.freecontent.community.media.service.impl;

import org.springframework.stereotype.Service;

import com.freecontent.community.media.config.UploadPolicyProperties;
import com.freecontent.community.media.dto.UploadPolicy;
import com.freecontent.community.media.service.UploadPolicyService;

@Service
public class UploadPolicyServiceImpl implements UploadPolicyService {

    private final UploadPolicyProperties properties;

    public UploadPolicyServiceImpl(UploadPolicyProperties properties) {
        this.properties = properties;
    }

    @Override
    public UploadPolicy getUploadPolicy() {
        return new UploadPolicy(properties.maxImageBytes(), properties.maxVideoBytes(),
                properties.allowedImageMimeTypes(), properties.allowedVideoMimeTypes(),
                properties.multipartThresholdBytes(), properties.maxPartCount(), properties.uploadUrlTtlSeconds());
    }
}
