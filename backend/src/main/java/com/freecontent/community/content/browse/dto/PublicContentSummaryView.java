package com.freecontent.community.content.browse.dto;

import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.user.dto.PublicUserView;
import io.swagger.v3.oas.annotations.media.*;
import java.time.Instant;
import java.util.List;

@Schema(name = "ContentSummary", requiredProperties = {"id", "kind", "title", "summary", "author", "coverAssetId", "tags",
        "firstPublishedAt", "publishedAt", "publishedRevisionId", "likeCount", "commentCount", "viewerHasLiked", "viewerHasBookmarked"})
public record PublicContentSummaryView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id, ContentKind kind,
        @Schema(minLength = 1, maxLength = 120) String title, @Schema(maxLength = 300) String summary, PublicUserView author,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String coverAssetId,
        @ArraySchema(maxItems = 5) List<TagView> tags, Instant firstPublishedAt, Instant publishedAt,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String publishedRevisionId,
        @Schema(minimum = "0") long likeCount, @Schema(minimum = "0") long commentCount,
        boolean viewerHasLiked, boolean viewerHasBookmarked) {
    public PublicContentSummaryView { tags = List.copyOf(tags); }
    public static PublicContentSummaryView from(PublicContentView value) {
        return new PublicContentSummaryView(value.id(), value.kind(), value.title(), value.summary(), value.author(), value.coverAssetId(),
                value.tags(), value.firstPublishedAt(), value.publishedAt(), value.publishedRevisionId(), value.likeCount(), value.commentCount(),
                value.viewerHasLiked(), value.viewerHasBookmarked());
    }
}
