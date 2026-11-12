package com.freecontent.community.content.browse.service.transaction;

import com.freecontent.community.content.browse.dto.PublicContentView;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.service.read.PublicContentAssembler;
import com.freecontent.community.user.service.read.PublicUserReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 详情保留原有查询顺序和完整性错误边界，纯投影与列表共用。 */
@Service
public class PublicContentTransactionService {
    private final PublicContentMapper contents;
    private final PublicUserReader users;
    private final PublicContentAssembler assembler;
    public PublicContentTransactionService(PublicContentMapper contents, PublicUserReader users, PublicContentAssembler assembler) {
        this.contents = contents; this.users = users; this.assembler = assembler;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicContentView read(String contentId) {
        var row = contents.findById(contentId);
        assembler.requirePublicRevision(row);
        var media = assembler.media(row, contents.findAssets(row.revisionId()));
        var tags = assembler.tags(contents.findTags(row.revisionId()));
        var author = users.read(row.authorId());
        assembler.requireAuthor(author);
        return assembler.content(row, media, tags, author, contents.countVisibleByAuthor(author.id()));
    }
}
