package br.com.quickpass.config;

import br.com.quickpass.domain.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * "Banco de dados" em memória do mock. Não é thread-safe por si só: toda
 * requisição HTTP e todo tick do agendador executam sob {@link #lock}, que
 * serializa o acesso (aceitável num mock; em produção isto viraria JPA + transações).
 */
@Component
public class Store {
    public final ReentrantLock lock = new ReentrantLock();

    public final Map<String, Usuario> usuarios = new LinkedHashMap<>();
    /** token → userId */
    public final Map<String, String> tokens = new HashMap<>();
    public final Map<String, Veiculo> veiculos = new LinkedHashMap<>();
    public final Map<String, Disponibilidade> disponibilidades = new HashMap<>();
    public final Map<String, TemplateDisponibilidade> templates = new HashMap<>(); // por proprietário
    public final Map<String, PreferenciasAnuncio> preferencias = new HashMap<>(); // por veículo
    /** veiculoId → última liberação com um toque (para o "desfazer"). */
    public final Map<String, Desfazer> desfazer = new HashMap<>();
    public final Map<String, Reserva> reservas = new LinkedHashMap<>();
    public final Map<String, Pagamento> pagamentos = new HashMap<>();
    /** chave "reservaId:TIPO" → vistoria (rascunho ou concluída). */
    public final Map<String, Vistoria> vistorias = new HashMap<>();
    public final Map<String, Caucao> caucoes = new HashMap<>();
    public final Map<String, Disputa> disputas = new LinkedHashMap<>();
    public final List<Avaliacao> avaliacoes = new ArrayList<>();
    public final List<Repasse> repasses = new ArrayList<>();
    public final List<Auditoria> auditoria = new ArrayList<>();
    public final List<EventoAnalitico> eventos = new ArrayList<>();
    public final List<Notificacao> notificacoes = new ArrayList<>();
    public final Map<String, BuscaSalva> buscasSalvas = new LinkedHashMap<>();
    public final Set<String> alertados = new HashSet<>(); // "buscaId:veiculoId"
    public final List<BuscaRegistrada> buscasRegistradas = new ArrayList<>();
    public final Map<String, java.time.Instant> semOfertaDesde = new HashMap<>();
    public final Map<String, EstadoRegua> reguas = new HashMap<>();
    public final Set<String> recusaramLembrete = new HashSet<>();
    public final List<Indicacao> indicacoes = new ArrayList<>();
    /** userId → identidade (anti-autoindicação). */
    public final Map<String, Identidade> identidades = new HashMap<>();
    public final Map<String, GrupoAtribuido> gruposExperimento = new HashMap<>();
    public final Set<String> comDesconto = new HashSet<>();
    public EstadoExperimento experimento;

    private final Map<String, Integer> sequencias = new HashMap<>();

    public int proximo(String nome, int inicial) {
        return sequencias.merge(nome, 1, Integer::sum) + inicial - 1;
    }

    public record Desfazer(List<java.time.LocalDate> adicionados, java.time.Instant expiraEm) {
    }


    public record EstadoRegua(int enviados, java.time.Instant ultimoEm) {
    }

    public record Identidade(String cpf, String dispositivo, String cartao) {
    }

    public record GrupoAtribuido(GrupoExperimento grupo, java.time.Instant em) {
    }
}
