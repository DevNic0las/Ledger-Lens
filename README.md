# Ledger Lens

[![CI](https://github.com/DevNic0las/Ledger-Lens/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/DevNic0las/Ledger-Lens/actions/workflows/ci.yml)

## 1. O que é o projeto

Ledger Lens é um núcleo de **ledger de dupla entrada** (double-entry) em Java 25 + Spring Boot.
O objetivo é registrar transações financeiras em que cada movimento tem débitos e créditos que
se anulam, e responder "qual é o saldo desta conta?" **derivando** a resposta dos lançamentos,
nunca lendo um saldo armazenado.

Isso importa em contexto fintech porque saldo guardado é saldo que pode divergir: se ele é
calculado a partir de lançamentos imutáveis, qualquer inconsistência vira um erro detectável
na soma, e não um número silenciosamente errado em uma coluna.

> **Estado:** etapas 1 a 4 concluídas — domínio, persistência JDBC, saldo em SQL, idempotência
> concorrente no banco e **API REST** com criação/leitura de conta e postagem de transação. O
> detalhe por etapa está na seção 5 e no histórico de [`docs/STATUS.md`](docs/STATUS.md).

## 2. Princípios de design

1. **Saldo é calculado, nunca guardado.** `LedgerRepository.balanceOf(accountId)` deriva o saldo
   dos lançamentos em uma única query SQL, respeitando o lado normal do tipo de conta
   (`ASSET`/`EXPENSE` → débito; `LIABILITY`/`EQUITY`/`REVENUE` → crédito). Não existe coluna de
   saldo no schema.
2. **Lançamentos são imutáveis no modelo.** `Transaction` e `Entry` são `record`, e
   `Transaction` faz `List.copyOf(entries)`. Postar não altera nem apaga lançamento anterior.
   O banco **não** impede `UPDATE`/`DELETE` — ver limitações na seção 6.
3. **Toda transação fecha.** O construtor de `Transaction` rejeita a transação se
   `soma(débitos) != soma(créditos)`, e exige mínimo de 2 lançamentos.
4. **Idempotência decidida pela constraint.** `transaction.idempotency_key` é `UNIQUE` e o
   `INSERT` usa `ON CONFLICT (idempotency_key) DO NOTHING`. Chave nova → `POSTED`; mesma chave
   com mesmo conteúdo → `DUPLICATE`; mesma chave com conteúdo diferente →
   `IdempotencyConflictException`. Não existe consulta prévia (`exists` + `save`).
5. **Dinheiro em `long` de centavos.** `Entry.amountCents` é `long` e precisa ser `> 0`.
   Nunca `double`/`float`. No banco, `amount_cents BIGINT` com `CHECK (amount_cents > 0)`.
6. **Uma transação de banco por escrita.** `save()` grava o cabeçalho e todos os lançamentos
   dentro do mesmo `TransactionTemplate`: ou tudo entra, ou nada entra.

## 3. Como rodar e testar

Pré-requisitos reais do que existe hoje:

- JDK 25 (o `pom.xml` fixa `java.version=25`).
- Docker rodando — os testes de integração sobem um Postgres 17 via Testcontainers.
- Não é preciso ter Maven instalado: use o wrapper (`./mvnw`).

### Testar (comando executado)

```bash
./mvnw -B verify
```

Resultado nesta inspeção: **127 testes, 0 falhas, `BUILD SUCCESS`**.

| Classe | Testes | O que prova |
|--------|-------:|-------------|
| `LedgerSchemaTest` | 10 | Constraints do schema (SQLState) |
| `JdbcLedgerRepositoryTest` | 13 | Gravação, leitura, idempotência, rollback, retry em transação do chamador |
| `JdbcLedgerRepositoryBalanceTest` | 9 | Saldo em SQL e paridade com o domínio nos 5 tipos de conta |
| `JdbcLedgerRepositoryConcurrencyTest` | 2 | Duas threads na mesma chave: 1 `POSTED` + 1 `DUPLICATE` |
| `JdbcAccountRepositoryTest` | 12 | Round-trip de conta, `IN (:ids)`, `findById`, `findAll`, unicidade `(name, currency)`, PK ≠ regra de negócio |
| `PostTransactionServiceTest` | 10 | Caso de uso: validação de contas, `POSTED`/`DUPLICATE`, conflito, atomicidade |
| `AccountBalanceServiceTest` | 3 | Saldo pela aplicação e 404 na conta inexistente |
| `AccountBalanceServiceErrorTest` | 3 | Falha de infraestrutura propaga e não vira 404 |
| `AccountQueryServiceTest` | 4 | `findById`/`findAll` contra Postgres; ausência vira 404, lista vazia não |
| `TransactionControllerTest` | 9 | Tradução de exceção para 201/200/409/422/400 |
| `AccountControllerTest` | 13 | `POST /accounts` (201/409/400), `GET /accounts` (200), `GET /accounts/{id}` (200/404/400), `GET /accounts/{id}/balance` (200/404/400) |
| `ApiEndToEndTest` | 7 | Servidor + Postgres reais: criação e leitura de conta, `Location`, idempotência, 409, 422, 400, 404 |
| `CreateAccountCommandTest` | 4 | Id gerado no servidor, campos obrigatórios, formato delegado ao domínio |
| `IdempotencyContentTest` | 7 | Identidade da idempotência (descrição + multiconjunto de lançamentos) |
| `AccountTest` | 7 | Invariantes de `Account` |
| `LedgerTest` | 7 | Regras em memória, incluindo concorrência |
| `TransactionTest` | 6 | Invariantes de `Transaction`/`Entry` |
| `LedgerlensApplicationTests` | 1 | Contexto sobe com o schema migrado |

### Rodar a aplicação

`src/main/resources/application.yaml` aponta para um Postgres 17 local em `localhost:55432`
(banco `ll`, usuário `postgres`, senha `pw`) e todo valor é sobrescrevível por variável de
ambiente, então `java -jar` sobe sozinho depois que o banco existe. O Flyway aplica `V1` e
`V2` no boot.

```bash
# 1) suba um Postgres 17 na porta que o application.yaml assume
docker run -d --rm --name ll-pg -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=ll -p 55432:5432 postgres:17

# 2) empacote e suba — sem variáveis de ambiente
./mvnw -B -DskipTests package
java -jar target/ledgerlens-0.0.1-SNAPSHOT.jar
```

Para apontar para outro banco, use as variáveis usuais:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://outro-host:5432/db \
SPRING_DATASOURCE_USERNAME=user \
SPRING_DATASOURCE_PASSWORD=secret \
java -jar target/ledgerlens-0.0.1-SNAPSHOT.jar
```

A aplicação sobe em `http://localhost:8080`. Endpoints da etapa 4:

| Endpoint | Respostas |
|----------|-----------|
| `POST /accounts` | `201` criada (devolve o `id` gerado e `Location: /accounts/{id}`) · `409` já existe `(name, currency)` · `400` corpo inválido (campo ausente, moeda fora de `[A-Z]{3}`, `type` desconhecido) |
| `GET /accounts` | `200` com a lista de contas, ordenada por `name` e `currency` · lista vazia é `[]` com `200`, não `404` |
| `GET /accounts/{id}` | `200` com a conta · `404` conta inexistente · `400` id que não é UUID |
| `GET /accounts/{id}/balance` | `200` com `balanceCents` · `404` conta inexistente · `400` id que não é UUID |
| `POST /transactions` | `201` gravado · `200` duplicata (devolve a original) · `409` chave com conteúdo diferente · `422` regra violada (desbalanceada, conta inexistente, valor ≤ 0) · `400` pedido malformado ou sem `Idempotency-Key` |
| `GET /actuator/health` | `200 {"status":"UP"}` |
| `GET /v3/api-docs` | `200`, agora com os paths reais |

Erros usam RFC 9457 (`application/problem+json`).

O `POST /accounts` não aceita `id` no corpo: o servidor gera, então o cliente não colide com
PK. O conflito de nome é decidido pela constraint `account_name_currency_key` (migration `V2`),
via `ON CONFLICT (name, currency) DO NOTHING`, e traduzido para
`AccountAlreadyExistsException` → `409`.

Por que `GET /accounts` não é `404` quando vazio: o recurso da URL é a **coleção**, e ela existe
com zero elementos. `404` fica reservado para `GET /accounts/{id}` com id que não está no banco —
nesse caso o recurso individual pedido não existe.

### Fluxo completo com `curl`

Com o banco do passo anterior de pé e a aplicação rodando, o roteiro abaixo exercita a API
inteira. Cada passo indica o status esperado; os oito primeiros são de conta, os demais cobrem
transação e saldo.

```bash
B=http://localhost:8080

# 1) lista vazia -> 200 [] (a coleção existe, só não tem elemento)
curl -s -w '\nHTTP=%{http_code}\n' $B/accounts

# 2) cria a conta de débito -> 201 e Location: /accounts/{id}
curl -s -i -X POST $B/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Conta Corrente","currency":"BRL","type":"ASSET"}'

# 3) cria a conta de crédito -> 201
curl -s -i -X POST $B/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Conta Receita","currency":"BRL","type":"REVENUE"}'

# 4) mesmo (name, currency) -> 409 vindo da constraint
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Conta Corrente","currency":"BRL","type":"ASSET"}'

# 5) campo obrigatório ausente -> 400, sem tocar no banco
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/accounts -H 'Content-Type: application/json' \
  -d '{"currency":"BRL","type":"ASSET"}'

# 6) moeda fora do ISO 4217 -> 400
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Caixa","currency":"BITCOIN","type":"ASSET"}'
```

Guarde os ids devolvidos nos passos 2 e 3 e siga:

```bash
CHK_ID=<id devolvido no passo 2>
REV_ID=<id devolvido no passo 3>

# 7) leitura da coleção -> 200 com as duas contas
curl -s -w '\nHTTP=%{http_code}\n' $B/accounts

# 8) leitura individual -> 200
curl -s -w '\nHTTP=%{http_code}\n' $B/accounts/$CHK_ID

# 9) id inexistente -> 404
curl -s -w '\nHTTP=%{http_code}\n' $B/accounts/99999999-9999-9999-9999-999999999999

TX="{\"description\":\"Pix\",\"entries\":[{\"accountId\":\"$CHK_ID\",\"direction\":\"DEBIT\",\"amountCents\":10000},{\"accountId\":\"$REV_ID\",\"direction\":\"CREDIT\",\"amountCents\":10000}]}"

# 10) posta a transação -> 201
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/transactions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: e2e-1' -d "$TX"

# 11) repete a mesma chave e o mesmo conteúdo -> 200 com o MESMO id
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/transactions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: e2e-1' -d "$TX"

# 12) mesma chave, descrição diferente -> 409
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/transactions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: e2e-1' -d "${TX/Pix/Pix outra}"

# 13) desbalanceada -> 422
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/transactions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: e2e-2' \
  -d "{\"description\":\"Torta\",\"entries\":[{\"accountId\":\"$CHK_ID\",\"direction\":\"DEBIT\",\"amountCents\":500},{\"accountId\":\"$REV_ID\",\"direction\":\"CREDIT\",\"amountCents\":400}]}"

# 14) conta inexistente -> 422 com a lista de ids que faltaram
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/transactions -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: e2e-3' \
  -d "{\"description\":\"Fantasma\",\"entries\":[{\"accountId\":\"$CHK_ID\",\"direction\":\"DEBIT\",\"amountCents\":100},{\"accountId\":\"99999999-9999-9999-9999-999999999999\",\"direction\":\"CREDIT\",\"amountCents\":100}]}"

# 15) sem Idempotency-Key -> 400
curl -s -w '\nHTTP=%{http_code}\n' -X POST $B/transactions -H 'Content-Type: application/json' -d "$TX"

# 16) saldo da conta de débito -> 200 com 10000 centavos
curl -s -w '\nHTTP=%{http_code}\n' $B/accounts/$CHK_ID/balance

# 17) saldo da conta de crédito -> 200 com 10000 centavos
curl -s -w '\nHTTP=%{http_code}\n' $B/accounts/$REV_ID/balance

# 18) saldo de conta inexistente -> 404
curl -s -w '\nHTTP=%{http_code}\n' $B/accounts/99999999-9999-9999-9999-999999999999/balance
```

Executado contra o jar e um Postgres 17 reais, os 18 passos responderam os status acima. Entre
execuções, limpe o banco: `TRUNCATE TABLE entry, transaction, account` — sem isso o passo 1 não
devolve `[]` e o passo 4 deixa de ser o primeiro conflito.

## 4. Estrutura de packages

```
com.github.devnic0las.ledgerlens
├── account/api                 AccountController (POST /accounts, GET /accounts,
│                               GET /accounts/{id}, GET /accounts/{id}/balance)
├── account/application         CreateAccountService, CreateAccountCommand,
│                               AccountQueryService, AccountBalanceService
├── account/domain              Account, AccountType, AccountRepository (porta)
├── account/domain/exception    AccountAlreadyExistsException, AccountNotFoundException
├── account/infra               JdbcAccountRepository (adaptador JDBC)
├── ledger/api                  TransactionController, ApiExceptionHandler
├── ledger/application          PostTransactionService, PostTransactionCommand
├── ledger/domain               Ledger, Transaction, Entry, Direction, PostResult,
│                               LedgerRepository (porta), IdempotencyContent,
│                               exception/IdempotencyConflictException
├── ledger/infrastructure       JdbcLedgerRepository (adaptador JDBC)
└── LedgerlensApplication       bootstrap Spring Boot
```

**Regra de dependência:** a camada de domínio não conhece Spring, SQL nem persistência.
Os subpacotes `account/domain` e `ledger/domain` não têm nenhum import de
`org.springframework`, nenhuma chamada `JdbcClient`/`sql(...)` e nenhum SQL literal
(verificado por grep com escopo em `*/domain/`).

O fluxo de dependência é sempre para dentro:

- **`domain`** contém o modelo, as invariantes e as **portas** `LedgerRepository` e
  `AccountRepository`. Não conhece JDBC, REST, Spring nem `TransactionTemplate`.
- **`application`** depende **só do domínio** e orquestra os casos de uso. É onde vive
  `@Transactional`: a fronteira transacional é uma decisão de caso de uso, não do controller
  nem do adaptador. Nenhuma classe daqui importa algo de `infra`.
- **`infra`/`infrastructure`** depende do domínio e **implementa as portas** — é onde vivem o
  SQL e o Spring de persistência.
- **`api`** é o adaptador de entrada: traduz HTTP em comando e exceção em status. Não contém
  regra de negócio; a validação de forma do corpo fica no DTO e a de domínio no construtor.
- **Domínio e infraestrutura nunca apontam para fora.** Quem liga as pontas é o Spring.

`account/domain` importa `ledger/domain/Direction` (via `AccountType.normalSide()`) e
`ledger/domain` importa `account/domain/Account` (em `Ledger`). É um ciclo entre pacotes de
domínio — aceitável hoje, mas a resolver se um dos dois crescer.

## 5. Estado atual

| # | Etapa | Estado |
|---|-------|--------|
| 1 | Schema Flyway e persistência JDBC | **CONCLUÍDA** — `V1`, `JdbcLedgerRepository`, constraints provadas por SQLState |
| 2 | Saldo calculado em SQL | **CONCLUÍDA** — uma query, sem coluna de saldo, paridade com o domínio nos 5 tipos |
| 3 | Idempotência e atomicidade no banco | **CONCLUÍDA** — `ON CONFLICT DO NOTHING`, concorrência testada, retry em transação do chamador |
| 4 | API REST | **CONCLUÍDA** — `POST /accounts`, `GET /accounts`, `GET /accounts/{id}`, `GET /accounts/{id}/balance`, `POST /transactions`; erros em RFC 9457 |
| 5 | Fechamento (e2e + README) | **CONCLUÍDA** — suíte com 127 testes e fluxo `curl` de 18 passos contra servidor e Postgres reais |

O que funciona hoje, pela API e contra o Postgres: criar e ler contas, postar transação com
idempotência, e consultar saldo derivado. Não há autenticação, paginação, estorno nem
imutabilidade imposta pelo banco — ver limitações.

## 6. Decisões e limitações

Decisões registradas que afetam o comportamento:

- **Idempotência compara a descrição mais o multiconjunto de lançamentos.** `IdempotencyContent`
  exige `description` idêntica (byte a byte, sem `trim`) **e** compara os multiconjuntos de
  `(accountId, direction, amountCents)`. `occurredAt` e `id` ficam de fora de propósito: são
  gerados pelo servidor a cada tentativa, e incluí-los faria todo retry legítimo ser lido como
  conteúdo diferente. Consequência: repetir a chave com a mesma conta/direção/valor mas outra
  descrição é `409`, não `DUPLICATE`. A quantidade de cada lançamento conta: dois débitos de
  100 não são iguais a um de 200.
- **`PostResult` só tem `POSTED` e `DUPLICATE`.** Conteúdo divergente na mesma chave não é um
  resultado: é exceção (`IdempotencyConflictException`).
- **A validação acontece toda no construtor do domínio**, para que seja impossível construir
  uma transação inválida — inclusive vinda de um futuro adaptador.
- **O schema não duplica as regras do domínio.** O banco garante `amount_cents > 0`,
  `direction ∈ {DEBIT, CREDIT}`, `type` conhecido, FKs e unicidade da chave. Não há trigger
  que force débitos = créditos nem que impeça `UPDATE`/`DELETE` em `entry`.
- **`ON CONFLICT (idempotency_key) DO NOTHING` em vez de `catch (DuplicateKeyException)`.**
  Violar a `UNIQUE` no Postgres aborta a **transação inteira** (SQLState `25P02`), então o
  `catch` só funcionava quando `save` abria a própria transação. Com `ON CONFLICT` não há erro,
  a transação do chamador sobrevive e `save` funciona dentro de um `@Transactional`.
  A constraint é **nomeada** no `ON CONFLICT`: colisão de PK (`transaction_pkey`) continua
  subindo como `DuplicateKeyException` e nunca é confundida com idempotência.
- **Ordem obrigatória nos casos de uso: `save` primeiro, efeitos colaterais depois, e só se
  `POSTED`.** `DUPLICATE` é sucesso e **commita** o que veio antes na transação; um retry não
  pode debitar uma conta duas vezes. Conflito de idempotência marca a transação como
  rollback-only e nada commita — o serviço não deve capturar essa exceção, ela tem que
  propagar para virar `409` na API.
- **A ordem dos lançamentos não importa.** A idempotência compara multiconjunto e o domínio
  trata `entries` como conjunto de movimentos; `findById` ordena por `id` só para leitura
  estável.
- **`GET /accounts` ordena por `name, currency`, não por data de criação.** Não existe coluna de
  timestamp na tabela `account`; ordenar por `id` seria arbitrário (UUID v4 não é monotônico) e
  a ordem mudaria sem motivo entre chamadas. A ordem escolhida é a mesma da constraint de
  unicidade, o que também torna a saída previsível nos testes.
- **Leitura de conta e leitura de saldo são serviços diferentes.** `AccountQueryService` lê a
  conta; `AccountBalanceService` deriva o saldo. O recurso é outro, e manter junto faria a
  leitura de atributos depender da query de saldo. `findById` devolve `Optional` na porta e o
  serviço só lança `AccountNotFoundException` no caso vazio — ausência é valor explícito, não
  falha de infraestrutura.

Limitações conhecidas:

- **Lançamentos mutáveis no banco.** Não há `REVOKE`/trigger que impeça `UPDATE`/`DELETE` em
  `entry`. A imutabilidade hoje é garantida só pelo modelo Java.
- **O banco não valida balanceamento nem moeda.** Aceita transação desbalanceada e mistura de
  moedas entre as contas de uma mesma transação; a regra existe apenas no domínio.
- **Bordas numéricas e `null` não são validadas no domínio.** Soma em `long` sem `Math.addExact`
  permite overflow que "fecha" uma transação desbalanceada; `direction = null` em `Entry` é
  aceito e contado como crédito; `Entry.id` repetido dentro da transação não é checado.
- **Sem estorno.** Não há operação de reversal nem vínculo entre um lançamento e seu original.
- **`idempotency_key` é global e sem escopo.** Não há separação por cliente/conta nem limite de
  tamanho; duas origens que usem a mesma chave colidem.
- **`GET /accounts` não pagina.** Devolve a tabela inteira em uma resposta. Com muitas contas
  isso vira payload e tempo de resposta proporcionais ao total; falta `LIMIT`/`OFFSET` ou
  cursor e um envelope com metadados.
- **Não há `GET /transactions`.** Transação só pode ser criada e relida pela chave de
  idempotência; não há como listar o histórico.
- **Sem autenticação.** Qualquer cliente que alcance a porta pode criar conta e postar
  transação. Não há usuário, dono de conta nem escopo por cliente.
- **Leitura header + entries em dois statements.** `findById`/`findByIdempotencyKey` leem o
  cabeçalho e depois os lançamentos sem transação de leitura; uma escrita concorrente entre os
  dois pode produzir leitura inconsistente.

## 7. Próximos passos

Somente o que está pendente, na ordem do plano:

A etapa do ledger está fechada. O que resta, fora deste escopo:

1. **Paginação em `GET /accounts`** e um `GET /transactions` para o histórico — hoje a única
   leitura de transação é pela chave de idempotência.
2. **Autenticação e escopo por cliente** — hoje qualquer um que alcance a porta escreve no razão.
3. **Correções de integridade** (PRs separados): imutabilidade no banco, validação de moeda no
   schema, bordas de `long`/`null` no domínio, escopo da chave de idempotência.
4. **`Entry.id` repetido dentro da transação** não é rejeitado — dívida conhecida, registrada em
   [`docs/STATUS.md`](docs/STATUS.md).
5. **Documentar a proteção de branch** no GitHub (o `README` não cobre regras de merge).
