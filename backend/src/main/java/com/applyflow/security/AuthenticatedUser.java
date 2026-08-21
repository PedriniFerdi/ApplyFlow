package com.applyflow.security;

import java.io.Serializable;

public interface AuthenticatedUser extends Serializable {
    Long userId();

    String email();
}
