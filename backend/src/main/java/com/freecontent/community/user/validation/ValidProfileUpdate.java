package com.freecontent.community.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = ProfileUpdateValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidProfileUpdate {
    String message() default "资料修改字段不合法";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
