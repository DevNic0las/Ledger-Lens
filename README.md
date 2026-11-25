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

> **Estado:** etapas 1, 2 e 3 concluídas — domínio, persistência JDBC, saldo em SQL e
> idempotência concorrente no banco. A **API REST não existe**. O detalhe por etapa está na
> seção 5 e no histórico de [`docs/STATUS.md`](docs/STATUS.md).

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

Resultado nesta inspeção: **102 testes, 0 falhas, `BUILD SUCCESS`**.

| Classe | Testes | O que prova |
|--------|-------:|-------------|
| `LedgerSchemaTest` | 10 | Constraints do schema (SQLState) |
| `JdbcLedgerRepositoryTest` | 13 | Gravação, leitura, idempotência, rollback, retry em transação do chamador |
| `JdbcLedgerRepositoryBalanceTest` | 9 | Saldo em SQL e paridade com o domínio nos 5 tipos de conta |
| `JdbcLedgerRepositoryConcurrencyTest` | 2 | Duas threads na mesma chave: 1 `POSTED` + 1 `DUPLICATE` |
| `JdbcAccountRepositoryTest` | 8 | Round-trip de conta, `IN (:ids)`, unicidade `(name, currency)`, PK ≠ regra de negócio |
| `PostTransactionServiceTest` | 10 | Caso de uso: validação de contas, `POSTED`/`DUPLICATE`, conflito, atomicidade |
| `AccountBalanceServiceTest` | 3 | Saldo pela aplicação e 404 na conta inexistente |
| `AccountBalanceServiceErrorTest` | 3 | Falha de infraestrutura propaga e não vira 404 |
| `TransactionControllerTest` | 9 | Tradução de exceção para 201/200/409/422/400 |
| `AccountControllerTest` | 8 | `POST /accounts` (201/409/400) e `GET /accounts/{id}/balance` (200/404/400) |
| `ApiEndToEndTest` | 7 | Servidor + Postgres reais: criação de conta, idempotência, 409, 422, 400, 404 |
| `CreateAccountCommandTest` | 6 | Id gerado no servidor, campos obrigatórios, formato delegado ao domínio |
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
| `POST /accounts` | `201` criada (devolve o `id` gerado e `Location`) · `409` já existe `(name, currency)` · `400` corpo inválido (campo ausente, moeda fora de `[A-Z]{3}`, `type` desconhecido) |
| `GET /accounts/{id}/balance` | `200` com `balanceCents` · `404` conta inexistente · `400` id que não é UUID |
| `POST /transactions` | `201` gravado · `200` duplicata (devolve a original) · `409` chave com conteúdo diferente · `422` regra violada (desbalanceada, conta inexistente, valor ≤ 0) · `400` pedido malformado ou sem `Idempotency-Key` |
| `GET /actuator/health` | `200 {"status":"UP"}` |
| `GET /v3/api-docs` | `200`, agora com os paths reais |

Erros usam RFC 9457 (`application/problem+json`).

O `POST /accounts` não aceita `id` no corpo: o servidor gera, então o cliente não colide com
PK. O conflito de nome é decidido pela constraint `account_name_currency_key` (migration `V2`),
via `ON CONFLICT (name, currency) DO NOTHING`, e traduzido para
`AccountAlreadyExistsException` → `409`.

## 4. Estrutura de packages

```
com.github.devnic0las.ledgerlens
├── account/domain              Account, AccountType
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

- **`domain`** contém o modelo, as invariantes e a **porta** `LedgerRepository`. Não conhece
  JDBC, REST, Spring nem `TransactionTemplate`.
- **`infrastructure`** depende do domínio e **implementa a porta** — é onde vivem o SQL e o
  Spring de persistência, como `ledger/infrastructure/JdbcLedgerRepository`.
- **Não existe camada `application`.** Os diretórios `account/api`, `account/application` e
  `account/infra` estão vazios e não versionados; não há caso de uso. É a maior lacuna atual.
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
| 4 | API REST | **PENDENTE** — nenhum controller; OpenAPI vazio |
| 5 | Fechamento (e2e + README) | **EM ANDAMENTO** — este README; falta e2e pela API, que depende da etapa 4 |

O que funciona hoje: as regras contábeis, o saldo derivado em SQL e a idempotência concorrente
**com persistência real no Postgres**, cobertas por 41 testes. O que não funciona: nada disso é
alcançável de fora do processo — não há endpoint, e também não há como criar conta pela
aplicação.

## 6. Decisões e limitações

Decisões registradas que afetam o comportamento:

- **Idempotência compara conta, direção e valor — e ignora a descrição.** `IdempotencyContent`
  reduz cada lançamento a `(accountId, direction, amountCents)` e compara os multiconjuntos.
  `description`, `occurredAt` e `id` da transação são ignorados. Consequência: repetir a chave
  com a mesma conta/direção/valor mas descrição diferente é tratado como `DUPLICATE`, não como
  erro.
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

Limitações conhecidas:

- **Sem API REST.** Nenhum endpoint de domínio; só Actuator e um OpenAPI vazio.
- **Sem camada de aplicação.** Nenhum `@Service`; a porta só é chamada pelos testes.
- **Sem criação de conta pela aplicação.** Não existe porta nem adaptador para `Account`;
  as contas dos testes são inseridas por SQL direto.
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
- **`application.yaml` não configura datasource**: rodar exige variáveis de ambiente.
- **`springdoc` está no classpath sem endpoints** para documentar.
- **Leitura header + entries em dois statements.** `findById`/`findByIdempotencyKey` leem o
  cabeçalho e depois os lançamentos sem transação de leitura; uma escrita concorrente entre os
  dois pode produzir leitura inconsistente.

## 7. Próximos passos

Somente o que está **PENDENTE**, na ordem do plano:

1. **Etapa 4 — API REST:** `POST /transactions` (201 sucesso, 200 duplicata, 409 conflito de
   chave, 400 desbalanceada) e `GET /accounts/{id}/balance`, com `@RestControllerAdvice` e
   corpo JSON consistente.
2. **Porta de `Account`** e caso de uso de criação de conta — sem isso não há como semear o
   ledger pela aplicação.
3. **Testes de API** (MockMvc) e `/v3/api-docs` com paths reais.
4. **Etapa 5 — fechamento:** teste ponta a ponta pela API e README final.
5. **Correções de integridade** (PRs separados): imutabilidade no banco, validação de moeda,
   bordas de `long`/`null` no domínio, escopo da chave de idempotência.
6. **Configurar datasource padrão** de desenvolvimento e documentar a proteção de branch.
