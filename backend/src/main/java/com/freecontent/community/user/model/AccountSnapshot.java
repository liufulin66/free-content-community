package com.freecontent.community.user.model;

/** 供其他业务模块复核账号的最小快照，不暴露密码、资料或持久化实体。 */
public record AccountSnapshot(String id, Long authVersion, boolean emailVerified, boolean active, boolean disabled) { }
