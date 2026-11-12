package com.freecontent.community.content.validation;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.model.ContentDraftData;
import com.freecontent.community.content.model.ContentKind;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;
import java.util.*;

/** 校验草稿输入，不承担发布时的READY及ACTIVE校验。 */
@Component
public class ContentDraftValidator {
    private final Parser parser = Parser.builder().build();

    public void validate(ContentDraftData input) {
        unique(input.imageAssetIds(), "imageAssetIds");
        unique(input.tagIds(), "tagIds");
        if (input.kind() == ContentKind.ARTICLE && input.videoAssetId() != null) {
            throw invalid("videoAssetId", "ARTICLE不能关联视频素材");
        }
        Set<String> images = new HashSet<>();
        parser.parse(input.bodyMarkdown()).accept(new AbstractVisitor() {
            @Override public void visit(Image image) {
                String destination = image.getDestination();
                if (!destination.matches("asset:[A-Za-z0-9_-]{1,64}")) {
                    throw invalid("bodyMarkdown", "正文图片必须使用asset:素材ID引用");
                }
                images.add(destination.substring("asset:".length()));
            }
        });
        if (!images.equals(new HashSet<>(input.imageAssetIds()))) {
            throw invalid("imageAssetIds", "正文图片引用必须与imageAssetIds一致");
        }
    }

    private void unique(List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) throw invalid(field, "数组元素不能重复");
    }

    private BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null, null,
                List.of(ProblemDetailResponse.Violation.body(field, message)));
    }
}
