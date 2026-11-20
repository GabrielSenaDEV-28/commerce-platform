package com.gabrielsena.commerce.customer.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.regex.Pattern;

/** Validates CPF syntax only; it does not verify ownership or registration status. */
public final class Cpf {
    private static final Pattern INPUT = Pattern.compile("[0-9]{11}|[0-9]{3}\\.[0-9]{3}\\.[0-9]{3}-[0-9]{2}");

    private Cpf() { }

    public static String normalize(String value) {
        String digits = normalizedOrNull(value);
        if (digits == null) {
            var problem = new Problem(HttpStatus.BAD_REQUEST, "Enter a valid CPF.");
            problem.getBody().setProperty("errors", List.of("cpf: Enter a valid CPF."));
            throw problem;
        }
        return digits;
    }

    public static boolean isValid(String value) {
        return normalizedOrNull(value) != null;
    }

    private static String normalizedOrNull(String value) {
        if (value == null || !INPUT.matcher(value.strip()).matches()) {
            return null;
        }
        String digits = value.strip().replace(".", "").replace("-", "");
        if (digits.chars().allMatch(digit -> digit == digits.charAt(0))
                || digits.charAt(9) - '0' != checkDigit(digits, 9)
                || digits.charAt(10) - '0' != checkDigit(digits, 10)) {
            return null;
        }
        return digits;
    }

    private static int checkDigit(String digits, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (digits.charAt(i) - '0') * (length + 1 - i);
        }
        return (sum * 10 % 11) % 10;
    }

    static Problem alreadyInUse() {
        var problem = new Problem(HttpStatus.CONFLICT, "An account already exists for the supplied CPF.");
        problem.getBody().setProperty("code", "CPF_ALREADY_IN_USE");
        return problem;
    }

    public static final class Problem extends ResponseStatusException {
        private Problem(HttpStatus status, String reason) {
            super(status, reason);
        }
    }
}
