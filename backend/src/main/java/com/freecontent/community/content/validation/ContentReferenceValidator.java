package com.freecontent.community.content.validation;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.dto.CreateContentRequest;
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
    public void validate(CreateContentRequest input,String owner) {
        var references = new ArrayList<Reference>();
        if (input.getCoverAssetId() != null) references.add(new Reference(input.getCoverAssetId(),"CONTENT_IMAGE","coverAssetId"));
        for (String id : input.getImageAssetIds()) references.add(new Reference(id,"CONTENT_IMAGE","imageAssetIds"));
        if (input.getVideoAssetId() != null) references.add(new Reference(input.getVideoAssetId(),"CONTENT_VIDEO","videoAssetId"));
        references.sort(Comparator.comparing(Reference::id));
        for (var reference : references) {
            var asset = assets.findOwnedForUpdate(reference.id(),owner);
            if (asset == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            if (!reference.scene().equals(asset.getScene())) throw new BusinessException(ErrorCode.VALIDATION_ERROR,"素材用途不匹配",null,null,
                    List.of(ProblemDetailResponse.Violation.body(reference.field(),"素材用途不匹配")));
        }
        for (String id : input.getTagIds().stream().sorted().toList()) {
            if (tags.selectById(id) == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
