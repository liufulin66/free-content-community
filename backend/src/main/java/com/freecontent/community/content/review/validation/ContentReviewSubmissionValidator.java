package com.freecontent.community.content.review.validation;

import com.freecontent.community.content.validation.ContentDraftValidator;
import com.freecontent.community.content.model.ContentDraftData;
import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.*;
import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.tag.mapper.TagMapper;
import com.freecontent.community.tag.model.TagStatus;
import org.springframework.stereotype.Component;
import java.util.*;

/** 聚合、修订已加锁后，按素材ID和标签ID排序加锁，确保提交时引用仍合法。 */
@Component
public class ContentReviewSubmissionValidator {
    private final ContentDraftValidator drafts;
    private final ContentReviewMediaValidator media;
    private final TagMapper tags;
    public ContentReviewSubmissionValidator(ContentDraftValidator drafts, ContentReviewMediaValidator media, TagMapper tags) {
        this.drafts = drafts; this.media = media; this.tags = tags;
    }
    public void validate(ContentWorkspace workspace, String actor) {
        var revision = workspace.workingRevision();
        if (revision.title().isBlank()) throw invalid("title", "提交审核时标题不能为空白");
        if (workspace.kind() == ContentKind.ARTICLE && revision.bodyMarkdown().isBlank()) throw invalid("bodyMarkdown", "文章正文不能为空白");
        if (workspace.kind() == ContentKind.VIDEO && revision.videoAssetId() == null) throw invalid("videoAssetId", "视频内容必须关联视频素材");
        var input = new ContentDraftData(workspace.kind(), revision.title(), revision.bodyMarkdown(),
                revision.coverAssetId(), revision.imageAssetIds(), revision.videoAssetId(), revision.tagIds());
        drafts.validate(input);
        media.validate(workspace.kind(), revision, actor);
        for (String id : revision.tagIds().stream().sorted().toList()) {
            var tag = tags.findByIdForUpdate(id);
            if (tag == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            if (tag.getStatus() != TagStatus.ACTIVE) throw invalid("tagIds", "提交审核只能使用有效标签");
        }
    }
    private BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null, null, List.of(ProblemDetailResponse.Violation.body(field, message)));
    }
}
