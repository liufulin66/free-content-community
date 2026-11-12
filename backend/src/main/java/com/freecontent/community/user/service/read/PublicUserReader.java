package com.freecontent.community.user.service.read;
import com.freecontent.community.user.mapper.PublicUserMapper;
import com.freecontent.community.user.model.PublicUserProfile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
/** 在调用方快照中读取公开资料；内容公开数量由内容模块计算。 */
@Component
public class PublicUserReader {
    private final PublicUserMapper users;
    public PublicUserReader(PublicUserMapper users){this.users=users;}
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public PublicUserProfile read(String id){return users.findById(id);}
    @Transactional(propagation=Propagation.MANDATORY,readOnly=true)
    public java.util.List<PublicUserProfile> readAll(java.util.List<String> ids) {
        return ids.isEmpty() ? java.util.List.of() : users.findByIds(ids);
    }
}
