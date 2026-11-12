package com.freecontent.community.content.service.read;

import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 内容模块提供公开可见数量；加入调用方快照，复用公开详情和列表的 VISIBLE 条件。 */
@Component
public class PublicContentCountReader {
    private final PublicContentMapper contents;

    public PublicContentCountReader(PublicContentMapper contents) {
        this.contents = contents;
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public long countByAuthor(String authorId) {
        return contents.countVisibleByAuthor(authorId);
    }
}
