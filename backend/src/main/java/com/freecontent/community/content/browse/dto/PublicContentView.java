package com.freecontent.community.content.browse.dto;

import com.freecontent.community.content.model.ContentKind;
import com.freecontent.community.tag.dto.TagView;
import com.freecontent.community.user.dto.PublicUserView;
import io.swagger.v3.oas.annotations.media.*;
import java.time.Instant;
import java.util.List;

@Schema(name = "PublicContent", requiredProperties = {"id", "kind", "title", "summary", "author", "coverAssetId", "tags",
        "firstPublishedAt", "publishedAt", "publishedRevisionId", "likeCount", "commentCount", "viewerHasLiked",
        "viewerHasBookmarked", "bodyMarkdown", "imageAssetIds", "videoAssetId"})
public record PublicContentView(
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String id, ContentKind kind,
        @Schema(minLength = 1, maxLength = 120) String title, @Schema(maxLength = 300) String summary,
        PublicUserView author,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String coverAssetId,
        @ArraySchema(maxItems = 5) List<TagView> tags,
        Instant firstPublishedAt, Instant publishedAt,
        @Schema(minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String publishedRevisionId,
        @Schema(minimum = "0") long likeCount, @Schema(minimum = "0") long commentCount,
        boolean viewerHasLiked, boolean viewerHasBookmarked, String bodyMarkdown,
        @ArraySchema(maxItems = 50, uniqueItems = true, schema = @Schema(type = "string", minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$")) List<String> imageAssetIds,
        @Schema(types = {"string", "null"}, minLength = 1, maxLength = 64, pattern = "^[A-Za-z0-9_-]+$") String videoAssetId) {
    public PublicContentView { tags = List.copyOf(tags); imageAssetIds = List.copyOf(imageAssetIds); }
}
