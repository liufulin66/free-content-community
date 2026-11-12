package com.freecontent.community.user.model;
import java.time.LocalDateTime;
/** 仅包含公开资料，避免内容浏览读取账号密码和邮箱。 */
public record PublicUserProfile(String id,String displayName,String bio,LocalDateTime createdAt) { }
