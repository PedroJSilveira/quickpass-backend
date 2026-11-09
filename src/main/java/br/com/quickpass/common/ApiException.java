package br.com.quickpass.common;

import org.springframework.http.HttpStatus;

/** Erro de negócio/acesso com o status HTTP correspondente. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String mensagem) {
        super(mensagem);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Regra de negócio violada (400). */
    public static ApiException regra(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }

    /** Conflito de estado/agenda (409). */
    public static ApiException conflito(String msg) {
        return new ApiException(HttpStatus.CONFLICT, msg);
    }

    public static ApiException naoEncontrado(String msg) {
        return new ApiException(HttpStatus.NOT_FOUND, msg);
    }

    public static ApiException naoAutenticado(String msg) {
        return new ApiException(HttpStatus.UNAUTHORIZED, msg);
    }

    public static ApiException proibido(String msg) {
        return new ApiException(HttpStatus.FORBIDDEN, msg);
    }
}
