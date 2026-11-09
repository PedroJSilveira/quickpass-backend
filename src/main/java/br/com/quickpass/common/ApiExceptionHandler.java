package br.com.quickpass.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return corpo(e.status(), e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacao(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return corpo(HttpStatus.BAD_REQUEST, "Dados inválidos.", campos);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> arquivoGrande(MaxUploadSizeExceededException e) {
        return corpo(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo maior que o limite permitido.", null);
    }

    @ExceptionHandler({MultipartException.class, MissingServletRequestPartException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<Map<String, Object>> envioIncompleto(Exception e) {
        String parte = e instanceof MissingServletRequestPartException m ? m.getRequestPartName()
                : e instanceof MissingServletRequestParameterException p ? p.getParameterName() : null;
        return corpo(HttpStatus.BAD_REQUEST, parte != null ? "Campo obrigatório ausente: " + parte + "." : "Envio de arquivo inválido (use multipart/form-data).", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> tipoNaoSuportado(HttpMediaTypeNotSupportedException e) {
        return corpo(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de conteúdo não suportado: " + e.getContentType() + ".", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> metodoNaoSuportado(HttpRequestMethodNotSupportedException e) {
        return corpo(HttpStatus.METHOD_NOT_ALLOWED, "Método não suportado nesta rota.", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> semRota(NoResourceFoundException e) {
        return corpo(HttpStatus.NOT_FOUND, "Rota não encontrada.", null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> requisicaoInvalida(Exception e) {
        return corpo(HttpStatus.BAD_REQUEST, "Requisição inválida: " + e.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> inesperado(Exception e) {
        log.error("Erro inesperado", e);
        return corpo(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno.", null);
    }

    private ResponseEntity<Map<String, Object>> corpo(HttpStatus status, String msg, Object campos) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.value());
        m.put("erro", status.getReasonPhrase());
        m.put("mensagem", msg);
        if (campos != null) m.put("campos", campos);
        return ResponseEntity.status(status).body(m);
    }
}
