package com.applyflow.dto.auth;

import java.util.ArrayList;
import java.util.List;

import com.applyflow.entity.UserAccount;

public record CurrentUserResponse(
        Long id,
        String fullName,
        String email,
        boolean emailVerified,
        List<String> authenticationMethods
) {
    public static CurrentUserResponse from(UserAccount user) {
        List<String> methods = new ArrayList<>();
        if (user.hasPassword()) {
            methods.add("PASSWORD");
        }
        if (user.hasGoogle()) {
            methods.add("GOOGLE");
        }
        return new CurrentUserResponse(
                user.getId(), user.getFullName(), user.getEmail(), user.isEmailVerified(), List.copyOf(methods));
    }
}
