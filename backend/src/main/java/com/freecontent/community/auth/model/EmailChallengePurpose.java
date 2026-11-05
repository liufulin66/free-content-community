package com.freecontent.community.auth.model;

//定义验证码用途枚举
public enum EmailChallengePurpose {


    //枚举把合法用途限定为注册和找回密码。后续保存数据库时也使用这两个名称，避免各处手写字符串产生拼写错误
    REGISTER,//注册
    PASSWORD_RESET//找回密码
}