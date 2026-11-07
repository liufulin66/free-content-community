package com.freecontent.community.media.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadPolicyConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(UploadPolicyConfiguration.class)
            .withPropertyValues(
                    "app.media.upload-policy.max-image-bytes=10485760",
                    "app.media.upload-policy.max-video-bytes=524288000",
                    "app.media.upload-policy.allowed-image-mime-types=image/jpeg,image/png",
                    "app.media.upload-policy.allowed-video-mime-types=video/mp4",
                    "app.media.upload-policy.multipart-threshold-bytes=16777216",
                    "app.media.upload-policy.max-part-count=10000",
                    "app.media.upload-policy.upload-url-ttl-seconds=600");

    @ParameterizedTest
    @ValueSource(strings = {"max-image-bytes=0", "max-image-bytes=9007199254740992",
            "max-video-bytes=-1", "max-video-bytes=9007199254740992", "multipart-threshold-bytes=0",
            "max-part-count=0", "max-part-count=10001", "upload-url-ttl-seconds=0"})
    void rejectsNumericValuesOutsideContractAtStartup(String override) {
        runner.withPropertyValues("app.media.upload-policy." + override)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void policyKeepsAnImmutableSnapshotOfConfiguredMimeTypes() {
        List<String> images = new ArrayList<>(List.of("image/png"));
        List<String> videos = new ArrayList<>(List.of("video/mp4"));
        var properties = new UploadPolicyProperties(1, 1, images, videos, 1, 1, 1);

        images.add("image/gif");
        videos.clear();

        assertThat(properties.allowedImageMimeTypes()).containsExactly("image/png");
        assertThat(properties.allowedVideoMimeTypes()).containsExactly("video/mp4");
        assertThrows(UnsupportedOperationException.class, () -> properties.allowedImageMimeTypes().clear());
        assertThrows(UnsupportedOperationException.class, () -> properties.allowedVideoMimeTypes().clear());
    }
}
