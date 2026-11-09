# QuickPass — Backend (Spring Boot, mock)

API REST que espelha **tudo o que o frontend Angular já faz** (`../quickpass-frontend`), com o estado
**em memória** e os serviços externos (KYC/biometria e gateway de pagamento) **mockados**. Serve para
desenvolver e demonstrar o frontend contra uma API real antes de existir persistência/integrações.

- Java 21 · Spring Boot 3.3 · Maven
- Sem banco: reiniciar a aplicação restaura os dados de demonstração (`config/DadosDemo`)
- Autenticação: `POST /api/auth/login` devolve um token opaco → `Authorization: Bearer <token>`

## Como rodar

```bash
mvn spring-boot:run      # http://localhost:8080  (CORS liberado para http://localhost:4200)
mvn test                 # 18 testes de integração (MockMvc) cobrindo os fluxos abaixo
```

Contas de demonstração (senha `senha123`, exceto o admin):

| Conta | Papéis | Observação |
|---|---|---|
| `admin@quickpass.com` / `admin123` | ADMIN | moderação, disputas, métricas, parâmetros |
| `mariana.souza@example.com` | LOCATARIO + PROPRIETARIO | KYC pendente (como no frontend); tem 2 veículos e reservas semente |
| `rafael.u9@example.com` | LOCATARIO | KYC aprovado |
| `p1@example.com` … `p6@example.com` | PROPRIETARIO | KYC aprovado; donos dos veículos `1`…`6` |

## Papéis

Um usuário pode ser **LOCATARIO**, **PROPRIETARIO** ou os dois (`PUT /api/me/papeis`); **ADMIN** só é
concedido por outro admin (`PUT /api/admin/usuarios/{id}/papeis`). Cabeçalhos opcionais:
`X-Papel-Ativo` (papel em uso, vai nos eventos analíticos) e `X-Session-Id`.

## Endpoints

**Conta** — `POST /api/auth/cadastro` (aceita `atribuicao`: UTMs e `ref` de indicação, first-touch) · `POST /api/auth/login` · `POST /api/auth/logout` ·
`GET /api/me` · `PUT /api/me/papeis` · `GET /api/me/kyc` · `POST /api/me/kyc/{DOC|CNH|SELFIE|BANCO}` (4 itens aprovam o KYC) ·
`PUT /api/me/consentimentos` · `POST /api/me/sessao` · `GET /api/me/health-score?papel=`

**Locatário** — `GET /api/veiculos?local&inicio&fim&categoria&precoMax` (público; sem placa/endereço exato) · `GET /api/veiculos/{id}` ·
`GET /api/veiculos/{id}/avaliacoes` · `POST /api/reservas` · `GET /api/reservas?como=locatario|proprietario` · `GET /api/reservas/{id}` ·
`POST /api/reservas/{id}/{continuar-kyc|pagar|cancelar}` · `GET /api/reservas/{id}/endereco` ·
`/api/reservas/{id}/vistorias/{CHECKIN|CHECKOUT}` (`GET`, `PUT` dados, `POST fotos/{chave}`, `POST aceite`, `POST concluir`) ·
`POST /api/reservas/{id}/avaliacoes` · `POST /api/disputas/{id}/contestacao`

**Proprietário** — `/api/proprietario/veiculos` (cadastrar, listar, editar) · `.../veiculos/{id}/disponibilidade` (+ `dias/{data}/alternar`) ·
`.../template-disponibilidade` · `.../veiculos/{id}/um-toque[/previa|/desfazer]` · `.../preferencias` · `.../sugestoes-datas` · `.../recompensa` ·
`.../simulacao-receita` · `/api/proprietario/solicitacoes` · `POST /api/reservas/{id}/{aprovar|rejeitar}` · `POST /api/reservas/{id}/avaria` ·
`/api/proprietario/{painel|ganhos}` · `/api/proprietario/demanda?regiao=` · `POST /api/proprietario/lembretes/recusar`

**Hábito e growth** — `/api/buscas-salvas` (CRUD, `PATCH .../pausa`, `GET .../correspondencias`; alimenta o Radar) · `/api/notificacoes` (+ `POST .../abrir`) ·
`GET /api/indicacoes/link` · `GET /api/indicacoes`

**Admin** — `/api/admin/veiculos` (moderação) · `/api/admin/disputas` (+ `POST .../analise`, `POST .../decisao`) · `/api/admin/metricas[/funil|/retencao]` ·
`/api/admin/experimento` · `/api/admin/config` (parâmetros da §11, editáveis em runtime) · `/api/admin/auditoria` · `/api/admin/eventos` · `/api/admin/usuarios`

**Utilitários de demonstração (admin)** — `POST /api/admin/relogio/avancar {"horas":N}` avança o relógio da aplicação e roda os jobs
(expira holds, libera caução, paga repasses, dispara Radar/lembretes) · `POST /api/admin/jobs/executar` · `POST /api/admin/simulacao/gateway`.

## Mocks

- **KYC** (`MockKycProvider`): aprova ao receber os 4 itens; CNH válida por 3 anos.
- **Pagamento** (`MockPaymentGateway`): cartão iniciado em `0000` recusa a cobrança; `0001` recusa a pré-autorização da caução; ou use `/api/admin/simulacao/gateway`.
- **Upload de fotos**: `POST .../fotos/{chave}` só marca a foto como recebida (sem arquivo).
- Valores monetários em `double` arredondado a 2 casas — suficiente para o mock; usar `BigDecimal` ao integrar um gateway real.

## Regras do frontend portadas

Máquina de estados da reserva (§4.2) com auditoria · hold de agenda com prazo (KYC, resposta do proprietário, pagamento) · caução só capturada com decisão
de disputa registrada · repasse só após `CONCLUIDA` + caução resolvida · avaliação bilateral com publicação por par/prazo · endereço exato só após KYC + pagamento.

**Geração de hábito**: busca salva + Radar de disponibilidade (RF14), liberar agenda com um toque + desfazer (RF11), preferências do anúncio e sugestão
de datas (RF17), lembrete de agenda anti-churn (RF07), alerta de demanda local (RF16), simulação de receita potencial (RF05) — todos exigem
consentimento, respeitam limites semanais e registram o dado que justificou cada envio (RNF03).

**Growth**: atribuição first-touch (RF08), indicação com bloqueio de autoindicação (RF10), experimento RF09 (isenção de taxa na 1ª locação, com grupos
imutáveis, orçamento e guardrail), métricas North Star / guardrails / funil por canal / retenção por coorte com retorno espontâneo x notificado (RF15).
