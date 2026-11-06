package com.freecontent.community.user.validation;

import com.freecontent.community.user.dto.UpdateProfileRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ProfileUpdateValidator implements ConstraintValidator<ValidProfileUpdate, UpdateProfileRequest> {
    @Override
    public boolean isValid(UpdateProfileRequest request, ConstraintValidatorContext context) {
        if (request == null) return true;
        boolean valid = true;
        context.disableDefaultConstraintViolation();
        if (!request.hasMutableField()) {
            context.buildConstraintViolationWithTemplate("至少提供一个待修改字段").addConstraintViolation();
            valid = false;
        }
        if (request.hasDisplayName() && (request.getDisplayName() == null || request.getDisplayName().isBlank())) {
            context.buildConstraintViolationWithTemplate("昵称不能为空").addPropertyNode("displayName").addConstraintViolation();
            valid = false;
        }
        if (request.hasBio() && request.getBio() == null) {
            context.buildConstraintViolationWithTemplate("简介不能为null，清空请提交空字符串")
                    .addPropertyNode("bio").addConstraintViolation();
            valid = false;
        }
        return valid;
    }
}
