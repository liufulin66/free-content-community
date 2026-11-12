package com.freecontent.community.user.service.transaction;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.service.read.PublicContentCountReader;
import com.freecontent.community.user.dto.PublicUserView;
import com.freecontent.community.user.service.read.PublicUserReader;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 公开资料与当前公开内容数共用同一快照；目标账号状态不扩大或缩小公开字段。 */
@Service
public class PublicUserTransactionService {
    private final PublicUserReader users;
    private final PublicContentCountReader contents;

    public PublicUserTransactionService(PublicUserReader users, PublicContentCountReader contents) {
        this.users = users;
        this.contents = contents;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicUserView read(String userId) {
        var profile = users.read(userId);
        if (profile == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        return new PublicUserView(profile.id(), profile.displayName(), profile.bio(), null,
                profile.createdAt().toInstant(ZoneOffset.UTC), contents.countByAuthor(profile.id()), 0);
    }
}
