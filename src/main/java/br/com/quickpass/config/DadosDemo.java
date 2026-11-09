package br.com.quickpass.config;

import br.com.quickpass.domain.*;
import br.com.quickpass.service.DepositService;
import br.com.quickpass.service.PricingService;
import br.com.quickpass.service.UsuarioService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Dados de demonstração (espelham a semente do frontend). Contas (senha {@code senha123}, exceto o admin):
 * admin@quickpass.com / admin123 · mariana.souza@example.com (locatária + proprietária, KYC pendente) ·
 * rafael.u9@example.com (locatário) · p1..p6@example.com (proprietários com KYC aprovado).
 */
@Component
public class DadosDemo implements ApplicationRunner {
    private static final String SENHA = "senha123";

    private final Store store;
    private final ClockService clock;
    private final UsuarioService usuarios;
    private final PricingService pricing;
    private final DepositService deposit;
    private final boolean habilitado;

    public DadosDemo(Store store, ClockService clock, UsuarioService usuarios, PricingService pricing, DepositService deposit,
                     @Value("${quickpass.seed.enabled:true}") boolean habilitado) {
        this.store = store;
        this.clock = clock;
        this.usuarios = usuarios;
        this.pricing = pricing;
        this.deposit = deposit;
        this.habilitado = habilitado;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!habilitado) return;
        store.lock.lock();
        try {
            semearUsuarios();
            semearVeiculos();
            semearReservas();
            semearRepasses();
            semearBuscas();
        } finally {
            store.lock.unlock();
        }
    }

    // ───────────── usuários ─────────────

    private Usuario usuario(String id, String nome, String email, String senha, int cpfBase, Set<Papel> papeis, double nota, int cnhAnos, boolean kycAprovado) {
        Usuario u = usuarios.criar(id, nome, email, senha, cpf(cpfBase), papeis, cnhAnos);
        u.nota = nota;
        if (kycAprovado) {
            u.kyc.doc = u.kyc.cnh = u.kyc.selfie = u.kyc.banco = u.kyc.aprovado = true;
            u.statusKyc = StatusKyc.APROVADO;
            u.validadeCnh = clock.hoje().plusYears(3);
        }
        return u;
    }

    private void semearUsuarios() {
        var prop = EnumSet.of(Papel.PROPRIETARIO);
        usuario("admin", "Administrador QuickPass", "admin@quickpass.com", "admin123", 100000001, EnumSet.of(Papel.ADMIN), 5.0, 0, true);
        Usuario mariana = usuario("u1", "Mariana Souza", "mariana.souza@example.com", SENHA, 100000002, EnumSet.of(Papel.LOCATARIO, Papel.PROPRIETARIO), 4.8, 6, false);
        mariana.membroDesde = LocalDate.of(2025, 3, 1);
        usuario("p1", "Lucas Almeida", "p1@example.com", SENHA, 100000003, prop, 4.9, 10, true);
        usuario("p2", "Camila Rocha", "p2@example.com", SENHA, 100000004, prop, 4.7, 8, true);
        usuario("p3", "Rafael Teixeira", "p3@example.com", SENHA, 100000005, prop, 5.0, 12, true);
        usuario("p4", "Bruno Antunes", "p4@example.com", SENHA, 100000006, prop, 4.8, 7, true);
        usuario("p5", "Marina Lopes", "p5@example.com", SENHA, 100000007, prop, 4.9, 9, true);
        usuario("p6", "Diego Santos", "p6@example.com", SENHA, 100000008, prop, 4.6, 5, true);
        usuario("u9", "Rafael Teixeira", "rafael.u9@example.com", SENHA, 100000009, EnumSet.of(Papel.LOCATARIO), 4.9, 11, true);
    }

    /** Gera um CPF válido a partir de 9 dígitos-base. */
    private static String cpf(int base) {
        String b = String.format("%09d", base % 1_000_000_000);
        int d1 = dv(b, 10), d2 = dv(b + d1, 11);
        return b + d1 + d2;
    }

    private static int dv(String s, int peso) {
        int soma = 0;
        for (int i = 0; i < s.length(); i++) soma += (s.charAt(i) - '0') * (peso - i);
        int r = soma * 10 % 11;
        return r == 10 ? 0 : r;
    }

    // ───────────── veículos ─────────────

    private static List<ItemManutencao> manut(String revisao, String oleo, String pneus) {
        return List.of(new ItemManutencao("Última revisão", revisao), new ItemManutencao("Troca de óleo", oleo),
                new ItemManutencao("Pneus", pneus), new ItemManutencao("Vistoria", "em dia"));
    }

    private void veiculo(String id, String foto, String marca, String modelo, int ano, String placa, CategoriaVeiculo cat, double diaria, String local,
                         String endereco, double dist, Cambio cambio, Combustivel comb, int km, String consumo, String dono, StatusVeiculo status,
                         Double nota, int locacoes, List<ItemManutencao> manutencao) {
        Veiculo v = new Veiculo();
        v.id = id;
        v.titulo = marca + " " + modelo + " " + ano;
        v.foto = foto;
        v.marca = marca;
        v.modelo = modelo;
        v.ano = ano;
        v.placa = placa;
        v.categoria = cat;
        v.diaria = diaria;
        v.local = local;
        v.enderecoExato = endereco;
        v.distanciaKm = dist;
        v.cambio = cambio;
        v.combustivel = comb;
        v.lugares = 5;
        v.km = km;
        v.consumo = consumo;
        v.proprietarioId = dono;
        v.status = status;
        v.nota = nota;
        v.locacoes = locacoes;
        v.manutencao = manutencao;
        store.veiculos.put(id, v);
    }

    private void semearVeiculos() {
        veiculo("1", "assets/civic.jpg", "Honda", "Civic", 2021, "FQP-4A12", CategoriaVeiculo.SEDA, 180, "Centro", "Rua Augusta, 1200 – Centro", 1.2,
                Cambio.AUTOMATICO, Combustivel.FLEX, 48320, "11 km/l", "p1", StatusVeiculo.APROVADO, 4.9, 23, manut("há 2 meses", "há 3.200 km", "trocados há 6 meses"));
        veiculo("2", "assets/argo.jpeg", "Fiat", "Argo", 2022, "HRT-2C90", CategoriaVeiculo.HATCH, 95, "Zona Sul", "Av. Santo Amaro, 800 – Zona Sul", 2.8,
                Cambio.MANUAL, Combustivel.FLEX, 62140, "13 km/l", "p2", StatusVeiculo.APROVADO, 4.7, 41, manut("há 1 mês", "há 1.800 km", "trocados há 4 meses"));
        veiculo("3", "assets/corolla-cross.jpg", "Toyota", "Corolla Cross", 2023, "JBK-9F31", CategoriaVeiculo.SUV, 240, "Aeroporto", "Av. Washington Luís, 3000 – Aeroporto", 6.5,
                Cambio.AUTOMATICO, Combustivel.HIBRIDO, 21880, "18 km/l", "p3", StatusVeiculo.APROVADO, 5.0, 12, manut("há 3 semanas", "há 900 km", "trocados há 2 meses"));
        veiculo("4", "assets/foto-a.jpeg", "BYD", "Dolphin", 2024, "KLM-3D77", CategoriaVeiculo.ELETRICO, 300, "Centro", "Rua Augusta, 1200 – Centro", 3.1,
                Cambio.AUTOMATICO, Combustivel.ELETRICO, 9450, "340 km/carga", "p4", StatusVeiculo.APROVADO, 4.8, 9, manut("há 1 mês", "não aplicável", "trocados há 5 meses"));
        veiculo("5", "assets/prius.jpg", "Toyota", "Prius", 2019, "NPQ-5G14", CategoriaVeiculo.HIBRIDO, 220, "Zona Norte", "Av. Cruzeiro do Sul, 450 – Zona Norte", 4.4,
                Cambio.AUTOMATICO, Combustivel.HIBRIDO, 84700, "30 km/l", "p5", StatusVeiculo.APROVADO, 4.9, 34, manut("há 2 meses", "há 4.100 km", "trocados há 8 meses"));
        veiculo("6", "assets/onix.avif", "Chevrolet", "Onix", 2020, "QRS-8H60", CategoriaVeiculo.HATCH, 85, "Zona Leste", "Av. Radial Leste, 900 – Zona Leste", 5.2,
                Cambio.MANUAL, Combustivel.FLEX, 71020, "13 km/l", "p6", StatusVeiculo.APROVADO, 4.6, 57, manut("há 4 meses", "há 5.600 km", "trocados há 10 meses"));
        // veículos da usuária de demonstração (persona proprietária)
        veiculo("u1-v1", "assets/civic.jpg", "Honda", "Civic", 2021, "MAR-1A23", CategoriaVeiculo.SEDA, 180, "Centro", "Rua Augusta, 1200 – Centro", 0,
                Cambio.AUTOMATICO, Combustivel.FLEX, 48320, "11 km/l", "u1", StatusVeiculo.APROVADO, 4.9, 23, manut("há 2 meses", "há 3.200 km", "trocados há 6 meses"));
        veiculo("u1-v2", "assets/foto-a.jpeg", "Fiat", "Mobi", 2019, "GDX-7B88", CategoriaVeiculo.HATCH, 70, "Centro", "Rua Augusta, 1200 – Centro", 0,
                Cambio.MANUAL, Combustivel.FLEX, 55000, "13 km/l", "u1", StatusVeiculo.EM_ANALISE, null, 0, List.of());
    }

    // ───────────── reservas / pagamentos ─────────────

    private Reserva reserva(String id, String veiculo, String locatario, LocalDate ini, LocalDate fim, Protecao prot, StatusReserva status, double diaria) {
        Reserva r = new Reserva();
        r.id = id;
        r.veiculoId = veiculo;
        r.locatarioId = locatario;
        r.inicio = ini;
        r.fim = fim;
        r.protecao = prot;
        r.status = status;
        r.valores = pricing.calcular(diaria, prot, ini, fim);
        r.criadaEm = clock.now();
        store.reservas.put(id, r);
        return r;
    }

    private void semearReservas() {
        LocalDate hoje = clock.hoje();
        Reserva a = reserva("QP-2291", "3", "u1", hoje.minusDays(75), hoje.minusDays(72), Protecao.COMPLETA, StatusReserva.EM_ANDAMENTO, 240);
        Reserva b = reserva("QP-2107", "2", "u1", hoje.minusDays(118), hoje.minusDays(116), Protecao.BASICA, StatusReserva.CONCLUIDA, 95);
        b.concluidaEm = clock.now().minusSeconds(116L * 86400);
        // Demonstra o check-in: confirmada com retirada hoje.
        Reserva c = reserva("QP-2330", "5", "u1", hoje, hoje.plusDays(2), Protecao.BASICA, StatusReserva.CONFIRMADA, 220);
        // Solicitação pendente de outro locatário para o veículo "1".
        Reserva d = reserva("QP-2310", "1", "u9", hoje.plusDays(30), hoje.plusDays(32), Protecao.COMPLETA, StatusReserva.SOLICITADA, 180);
        d.finalidade = "Viagem de trabalho";
        d.holdExpiraEm = clock.maisHoras(12);
        d.aprovacaoExpiraEm = d.holdExpiraEm;

        deposit.semear("QP-2291", a.valores.caucao(), StatusCaucao.PRE_AUTORIZADA);
        deposit.semear("QP-2107", b.valores.caucao(), StatusCaucao.LIBERADA);
        deposit.semear("QP-2330", c.valores.caucao(), StatusCaucao.PRE_AUTORIZADA);
        for (Reserva r : List.of(a, b, c)) {
            Pagamento p = new Pagamento();
            p.reservaId = r.id;
            p.valorDiaria = r.valores.diaria();
            p.taxaPlataforma = r.valores.taxa();
            p.total = r.valores.total();
            p.status = StatusPagamento.FINALIZADO;
            p.pagoEm = clock.now();
            store.pagamentos.put(r.id, p);
        }
    }

    private void repasse(String reserva, String veiculo, String dono, String titulo, String periodo, double bruto, int diasAtras) {
        Repasse r = new Repasse();
        r.reservaId = reserva;
        r.veiculoId = veiculo;
        r.proprietarioId = dono;
        r.veiculoTitulo = titulo;
        r.periodo = periodo;
        r.bruto = bruto;
        r.taxa = bruto * PricingService.TAXA_PCT / 100;
        r.liquido = bruto - r.taxa;
        r.status = StatusRepasse.PAGO;
        r.pagoEm = clock.hoje().minusDays(diasAtras);
        store.repasses.add(r);
    }

    private void semearRepasses() {
        repasse("QP-2200", "u1-v1", "u1", "Honda Civic 2021", "14 – 17 ago", 540, 4);
        repasse("QP-2188", "u1-v1", "u1", "Honda Civic 2021", "02 – 04 ago", 360, 14);
        repasse("QP-2151", "u1-v1", "u1", "Honda Civic 2021", "26 – 27 jul", 180, 30);
        repasse("QP-2130", "u1-v2", "u1", "Fiat Mobi 2019", "18 – 21 jul", 210, 38);
    }

    /** Histórico de buscas por região na última semana (base dos sinais de demanda). */
    private void semearBuscas() {
        Instant agora = clock.now();
        record Volume(String regiao, int n) {
        }
        for (Volume v : List.of(new Volume("Centro", 24), new Volume("Zona Sul", 12), new Volume("Aeroporto", 3))) {
            for (int i = 0; i < v.n(); i++) {
                store.buscasRegistradas.add(new BuscaRegistrada(v.regiao(), "Todos", null, null, agora.minusSeconds((i % 6) * 86400L + 3600)));
            }
        }
    }
}
