package br.com.quickpass.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/** Metadados de um arquivo enviado (o conteúdo fica em disco, ver StorageService). */
public class Arquivo {
    public String id;
    public String nome;
    public String contentType;
    public long tamanho;
    public Instant enviadoEm;
    @JsonIgnore public CategoriaArquivo categoria;
    @JsonIgnore public String donoId;
    /** Reserva a que o arquivo pertence (vistoria, avaria, contestação); null nos demais. */
    @JsonIgnore public String reservaId;
    /** Nome do arquivo em disco (gerado pelo servidor; nunca derivado do nome enviado). */
    @JsonIgnore public String armazenado;

    public String getUrl() {
        return "/api/arquivos/" + id;
    }
}
