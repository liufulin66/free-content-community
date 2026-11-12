package com.freecontent.community.content.review.validation;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.RevisionView;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** 提交和批准共用素材发布门槛；在内容/修订已锁定的事务内，按ID排序加锁。 */
@Component
public class ContentReviewMediaValidator {
    private final MediaAssetMapper assets;
    public ContentReviewMediaValidator(MediaAssetMapper assets) { this.assets = assets; }
    public void validate(ContentKind kind, RevisionView revision, String owner) {
        if (kind == ContentKind.ARTICLE && revision.videoAssetId() != null) throw invalid("videoAssetId", "ARTICLE不能关联视频素材");
        if (kind == ContentKind.VIDEO && revision.videoAssetId() == null) throw invalid("videoAssetId", "视频内容必须关联视频素材");
        var scenes = new TreeMap<String, String>();
        if (revision.coverAssetId() != null) scenes.put(revision.coverAssetId(), "CONTENT_IMAGE");
        for (String asset : revision.imageAssetIds()) scenes.put(asset, "CONTENT_IMAGE");
        if (revision.videoAssetId() != null && scenes.put(revision.videoAssetId(), "CONTENT_VIDEO") != null)
            throw invalid("videoAssetId", "同一素材不能同时作为图片和视频");
        boolean unready = false;
        for (var reference : scenes.entrySet()) {
            var asset = assets.findOwnedForUpdate(reference.getKey(), owner);
            if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            if (!reference.getValue().equals(asset.getScene())) throw invalid("revisionId", "修订引用的素材用途不匹配");
            if (!"READY".equals(asset.getState())) unready = true;
        }
        if (unready) throw new BusinessException(ErrorCode.MEDIA_NOT_READY);
    }
    private BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null, null, List.of(ProblemDetailResponse.Violation.body(field, message)));
    }
}
