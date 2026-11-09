# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Contexto

API Spring Boot (Java 21, Maven) que **espelha os services do frontend** em `../quickpass-frontend` — as regras de negócio vêm de lá (comentários citam RFs/§ da especificação; cada service Java corresponde a um `*.service.ts`). Estado **em memória**, KYC e pagamento **mockados**. Veja `README.md` para endpoints e contas de demonstração.

## Comandos

```bash
mvn spring-boot:run                                   # http://localhost:8080
mvn test                                              # todos os testes (integração, MockMvc)
mvn test -Dtest=LocacaoFluxoTest#locacaoCompletaAteRepasseEAvaliacao   # um teste
```

## Arquitetura

- **Sem banco.** `config/Store` guarda todas as coleções; `config/DadosDemo` repopula a semente a cada start. **Todo acesso é serializado por `Store.lock`**: `AuthInterceptor` trava a requisição inteira e `SchedulerService.tick()` trava o job — por isso services e entidades usam coleções/campos mutáveis comuns, sem sincronização própria. Código novo que rode fora de uma requisição ou do scheduler (ex.: outra thread) precisa pegar esse lock.
- **Camadas dos services (sem ciclos de injeção):** `Store/Clock/Config/Audit/EventBus` → `Usuario`, `Referral`, `Notification`, `Demand`, `Review`, `Agenda` (conflito de reservas + calendário do proprietário) → `Catalog`, `Experiment`/`Metrics` → `Booking`, `Deposit`, `Payout` → `Dispute`, `SavedSearch`, `AgendaReminder`, `LocalDemandAlert` → `SchedulerService`. `Metrics` e `Agenda` leem o `Store` direto de propósito, para não depender de `Booking`.
- **`BookingService.mover()` é a única porta de mudança de status da reserva** (valida `StatusReserva.podeIrPara`, audita). Caução só muda via `DepositService`; captura exige `DecisaoDisputa` completa.
- **Tempo:** toda regra com prazo usa `ClockService` (nunca `Instant.now()`/`LocalDate.now()`), que tem deslocamento ajustável (`POST /api/admin/relogio/avancar`) — é assim que os testes e a demo "passam o tempo". Parâmetros de negócio são `Parametro` lidos via `AppConfigService` (editáveis em runtime), nunca literais.
- **Auth:** token opaco em `Store.tokens`; `AuthContext` (ThreadLocal) expõe o usuário. Endpoints decidem o acesso chamando `AuthContext.usuario()` / `exigir(Papel)`; não há lista de rotas públicas. Erros de negócio são `ApiException` (400 regra, 409 conflito, 403, 404, 401) → `ApiExceptionHandler`.
- **Eventos analíticos** (`EventBus`) usam `snake_case` iguais ao frontend (`event-names.ts`); o funil/retenção em `MetricsService` lê esses eventos, então renomear uma prop (`user_id`, `locatario_id`, `owner_id`, `canal_origem`…) quebra as métricas.
- Contratos de entrada/saída ficam em `web/Dtos`; entidades de `domain/` são serializadas direto (campos públicos). Enums de veículo (`CategoriaVeiculo`, `Cambio`, `Combustivel`) serializam pelo rótulo ("Sedã"); os demais pelo nome da constante.

## Testes

`ApiTestBase` sobe o app com contexto **novo por teste** (`@DirtiesContext`) e traz helpers: `novoUsuario`, `reservaConfirmada`, `vistoria`, `avancarHoras`, `jobs`. O agendador fica desligado na prática (intervalo de 1h); os jobs rodam via `jobs()`/`avancarHoras()`. Os helpers `admin()`/`login()` geram evento `sessao_iniciada`, então asserts sobre eventos devem filtrar por `userId`.
