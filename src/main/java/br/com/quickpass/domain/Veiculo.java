package br.com.quickpass.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class Veiculo {
    public String id;
    public String titulo;
    /** Foto de capa (primeira de {@link #fotos}); vazia só em anúncios sem upload. */
    public String foto;
    /** Fotos do anúncio: URLs de /api/arquivos/{id} (ou assets estáticos da carga de demonstração). */
    public List<String> fotos = new ArrayList<>();
    /** Documentos enviados (CRLV, seguro): só o proprietário e o admin baixam. */
    public List<Arquivo> documentos = new ArrayList<>();
    public String marca;
    public String modelo;
    public int ano;
    public String placa;
    public CategoriaVeiculo categoria;
    public double diaria;
    /** Localização APROXIMADA (bairro) — única exibida em busca/público (RNF02). */
    public String local;
    /** Endereço exato: só revelado após KYC aprovado e pagamento confirmado (RNF02). */
    public String enderecoExato;
    public double distanciaKm;
    public Cambio cambio;
    public Combustivel combustivel;
    public int lugares;
    public int km;
    public String consumo;
    public String proprietarioId;
    public StatusVeiculo status = StatusVeiculo.EM_ANALISE;
    /** Nota cadastrada (fallback quando não há avaliações publicadas). */
    public Double nota;
    public int locacoes;
    public List<ItemManutencao> manutencao = new ArrayList<>();
    /** Validade de documentação/seguro. Vencido bloqueia publicar agenda (RF11). */
    public LocalDate docValidoAte;
    public String motivoRecusa;
}
