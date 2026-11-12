package com.freecontent.community.content.validation;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.model.ContentDraftData;
import com.freecontent.community.media.mapper.MediaAssetMapper;
import com.freecontent.community.tag.mapper.TagMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** 创建、保存草稿共用归属和用途校验；在已授权业务事务内调用，既有内容先锁定聚合。 */
@Component
public class ContentReferenceValidator {
    private final MediaAssetMapper assets;
    private final TagMapper tags;
    public ContentReferenceValidator(MediaAssetMapper assets,TagMapper tags) { this.assets = assets; this.tags = tags; }
    private record Reference(String id,String scene,String field) { }
    public void validate(ContentDraftData input,String owner) {
        var references = new ArrayList<Reference>();
        if (input.coverAssetId() != null) references.add(new Reference(input.coverAssetId(),"CONTENT_IMAGE","coverAssetId"));
        for (String id : input.imageAssetIds()) references.add(new Reference(id,"CONTENT_IMAGE","imageAssetIds"));
        if (input.videoAssetId() != null) references.add(new Reference(input.videoAssetId(),"CONTENT_VIDEO","videoAssetId"));
        references.sort(Comparator.comparing(Reference::id));
        for (var reference : references) {
            var asset = assets.findOwnedForUpdate(reference.id(),owner);
            if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            if (!reference.scene().equals(asset.getScene())) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"素材用途不匹配",null,null,
                    List.of(ProblemDetailResponse.Violation.body(reference.field(),"素材用途不匹配")));
        }
        for (String id : input.tagIds().stream().sorted().toList()) {
            if (tags.selectById(id) == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
