package com.unlam.pawgate.api.dto;

/**
 * DTOs (Data Transfer Objects) para los endpoints /auth/*.
 *
 * Cada clase tiene campos public que Gson serializa/deserializa por reflexion.
 * Mantengo las request/response juntas en un solo file para no spamear con
 * 6 archivos chiquitos.
 */
public final class AuthDtos {

    private AuthDtos() {}

    // ===== POST /auth/signup =====

    public static final class SignupRequest {
        public final String email;
        public final String password;
        public final String name;

        public SignupRequest(String email, String password, String name) {
            this.email = email;
            this.password = password;
            this.name = name;
        }
    }

    public static final class SignupResponse {
        public String message;
        public String email;
    }

    // ===== POST /auth/confirm =====

    public static final class ConfirmRequest {
        public final String email;
        public final String code;

        public ConfirmRequest(String email, String code) {
            this.email = email;
            this.code = code;
        }
    }

    public static final class ConfirmResponse {
        public String message;
    }

    // ===== POST /auth/login =====

    public static final class LoginRequest {
        public final String email;
        public final String password;

        public LoginRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }
    }

    public static final class LoginResponse {
        public String idToken;
        public String accessToken;
        public String refreshToken;
        public int expiresIn;  // segundos
    }

    // ===== Error response generico =====

    public static final class ApiError {
        public String error;
    }
}
