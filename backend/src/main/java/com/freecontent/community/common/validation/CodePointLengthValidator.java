package com.freecontent.community.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CodePointLengthValidator implements ConstraintValidator<CodePointLength, String> {
    private int min;
    private int max;

    @Override
    public void initialize(CodePointLength constraint) {
        min = constraint.min();
        max = constraint.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }
}
