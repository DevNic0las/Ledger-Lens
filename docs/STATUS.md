# Estado atual do ledgerlens

> **Este documento é histórico, não um retrato do presente.** Cada seção abaixo descreve o
> repositório **no momento da inspeção indicada**. Seções antigas não são reescritas: quando o
> estado muda, uma nova inspeção datada é acrescentada ao final. Para o estado corrente, leia a
> **última inspeção** ([27/11/2026](#inspeção-de-2026-11-27--etapa-4-get-accounts)).

Relatório gerado a partir da inspeção do repositório na branch `docs/readme-and-status`
(base: `main` em `cdffa99`). Nada aqui foi inferido de intenção ou de mensagem de commit:
cada afirmação tem arquivo, teste ou saída de comando como evidência.

Data da inspeção: 2026-11-17.

---

## Resumo

| # | Item | Estado |
|---|------|--------|
| 1 | Domínio em Java puro (`account/domain` e `ledger/domain`, sem Spring/SQL) | **FEITO** |
| 2 | Regras do domínio | **FEITO** (só em memória) |
| 3 | Schema Flyway e testes das constraints | **FEITO** — 8 testes versionados |
| 4 | Repositórios JDBC (Ledger grava/lê do Postgres) | **FEITO** — porta no domínio, adaptador `JdbcClient` |
| 5 | Saldo calculado em SQL | **FEITO** — um `SUM`/`GROUP BY`, sem coluna de saldo |
| 6 | Idempotência no banco com concorrência | **NÃO FEITO** (só a constraint, sem consumo) |
| 7 | API REST | **NÃO FEITO** (existe só Actuator/OpenAPI vazio) |
| 8 | CI e proteção da `main` | **PARCIAL** (CI FEITO, proteção não verificável) |
| 9 | PgVectorStore / JPA | **NÃO FEITO** (dependências sem uso) |

---

## 1. Domínio em Java puro — FEITO

A camada de domínio (`account/domain` e `ledger/domain`) é Java puro: sem Spring, sem SQL e
sem persistência. `application` e `infrastructure` (por exemplo `ledger/infrastructure`) podem
usar Spring, mas dependem do domínio — nunca o contrário.

Evidência (comando executado na raiz do repositório, escopo restrito a `*/domain/`):

```
$ grep -rnE "springframework|JdbcClient|SELECT|INSERT|sql\(" src/main/java --include=*.java -r | grep "/domain/"
(sem saída)
```

O escopo é `*/domain/` de propósito: `src/main/java/.../ledger` contém também
`ledger/infrastructure`, onde o adaptador JDBC e o SQL devem morar.

Também não há `jakarta.persistence`, `@Entity` nem `@Repository` em `*/domain/`.

Arquivos que compõem o domínio:

- `src/main/java/com/github/devnic0las/ledgerlens/account/domain/Account.java`
- `src/main/java/com/github/devnic0las/ledgerlens/account/domain/AccountType.java`
- `src/main/java/com/github/devnic0las/ledgerlens/ledger/domain/Direction.java`
- `src/main/java/com/github/devnic0las/ledgerlens/ledger/domain/Entry.java`
- `src/main/java/com/github/devnic0las/ledgerlens/ledger/domain/Transaction.java`
- `src/main/java/com/github/devnic0las/ledgerlens/ledger/domain/Ledger.java`
- `src/main/java/com/github/devnic0las/ledgerlens/ledger/domain/PostResult.java`

O único acoplamento com Spring no projeto é `LedgerlensApplication.java` (bootstrap) e o
`pom.xml`.

## 2. Regras do domínio — FEITO

| Regra | Onde está | Teste que prova |
|-------|-----------|-----------------|
| Valor positivo | `Entry` (construtor lança se `amountCents <= 0`) | `TransactionTest.shouldRejectZeroAmount` |
| Mínimo de 2 lançamentos | `Transaction` (`entries.size() < 2`) | `TransactionTest.shouldRejectEmptyTransaction` |
| Débitos = créditos | `Transaction` (`debits != credits` → `IllegalArgumentException`) | `TransactionTest.shouldRejectTransactionUnbalanced` e `shouldAcceptTransactionBalanced` |
| Saldo calculado | `Ledger.balanceOf(...)` percorre os lançamentos e respeita `AccountType.normalSide()` | `LedgerTest.balanceRespectsNormalSideOfEachAccountType` |
| Idempotência com checagem de conteúdo | `Ledger.post(...)` + `sameContent(...)` | `LedgerTest.postingSameKeyTwiceReturnsDuplicateAndKeepsBalance`, `LedgerTest.sameKeyWithDifferentContentThrows`, `LedgerTest.failedPostDoesNotBurnIdempotencyKey` |
| Conta inexistente é recusada | `Ledger.post(...)` valida `accounts.containsKey` | `LedgerTest.shouldRejectTransactionWithUnknownAccount` |

Ressalvas:

- Tudo isso vive **apenas no `Ledger` em memória**. Nenhuma dessas regras está no banco.
- A comparação de conteúdo da idempotência (`Ledger.shapesOf`) usa apenas
  `(accountId, direction, amountCents)`. **`description`, `occurredAt` e `id` do
  `Transaction` são ignorados**: repetir a chave com a mesma conta/direção/valor mas
  descrição diferente retorna `DUPLICATE` (não lança).
- `Ledger` usa `HashMap`/`ArrayList` sem sincronização → **não é thread-safe**.

## 3. Schema Flyway e testes das constraints — FEITO

A migration existe, é aplicada, e as constraints têm prova versionada.

- `src/main/resources/db/migration/V1__create_ledger_schema.sql` cria `account`,
  `transaction` e `entry`, com `CHECK` de `type`, `CHECK` de `direction`,
  `CHECK (amount_cents > 0)`, `UNIQUE (idempotency_key)` e duas FKs.
- Confirmado rodando a aplicação contra um Postgres 17 local: o Flyway aplicou
  `Migrating schema "public" to version "1 - create ledger schema"` e as tabelas
  `account`, `transaction`, `entry` + `flyway_schema_history` ficaram no banco.
- `LedgerlensApplicationTests.contextLoads` sobe o contexto com Testcontainers
  (`postgres:17`) e passa, o que prova que a migration roda em schema vazio.

**Os testes das constraints estão no repositório**, em
`src/test/java/com/github/devnic0las/ledgerlens/LedgerSchemaTest.java`, e rodam no
`./mvnw verify`:

```
$ git ls-files src/test/java/com/github/devnic0las/ledgerlens/LedgerSchemaTest.java
src/test/java/com/github/devnic0las/ledgerlens/LedgerSchemaTest.java

$ ./mvnw -B verify
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in ...LedgerSchemaTest
```

Os 8 testes cobrem: `amount_cents` zero, negativo, `type` de conta inválido, `direction`
inválida, FK de conta inexistente, FK de transação inexistente, `idempotency_key`
duplicada (SQLState `23505`) e um caminho feliz que grava conta, transação e dois
lançamentos. Os asserts usam SQLState, nunca mensagem de texto.

Nota histórica: o commit `cdffa99`, cuja mensagem afirmava cobrir as constraints com
testes JDBC, trouxe apenas o `.sql`. Durante um período o teste existiu só como artefato
órfão em `target/surefire-reports`. Ele foi versionado no commit `d3c1cc6` (PR #4), e
desde então é compilado e executado pelo `verify`.

## 4. Repositórios JDBC — FEITO

A porta está no domínio e a implementação está na infraestrutura:

- `src/main/java/.../ledger/domain/LedgerRepository.java` — interface sem Spring e sem SQL,
  com `save`, `findById`, `findByIdempotencyKey` e `balanceOf`.
- `src/main/java/.../ledger/infrastructure/JdbcLedgerRepository.java` — implementação com
  `JdbcClient`, anotada como `@Repository`.

Evidência:

```
$ grep -rn "interface LedgerRepository" src/main/java
.../ledger/domain/LedgerRepository.java:10:public interface LedgerRepository {

$ grep -rn "class JdbcLedgerRepository" src/main/java
.../ledger/infrastructure/JdbcLedgerRepository.java:29:public class JdbcLedgerRepository implements LedgerRepository {
```

O `save` grava a transação e todos os lançamentos dentro de um único `TransactionTemplate`:
se um lançamento falha, nada é gravado. A idempotência é decidida pela `UNIQUE` de
`idempotency_key` (SQLState `23505`), traduzida para `PostResult.DUPLICATE`.

Prova (teste, não inspeção):
`JdbcLedgerRepositoryTest` — 6 testes, incluindo round-trip da transação com lançamentos,
duplicata gravada uma vez só e `failingEntrySavesNothing` (rollback quando um lançamento
falha). Sem passar o `TransactionTemplate`, esse último falha com `expected: <0> but was: <1>`.

**Limite do que está provado:** persistência e idempotência por `UNIQUE` estão provadas
por teste de integração, mas ainda **não são expostas por API nem por serviço de
aplicação**. Não existe `@Service` nem `@RestController` em `src/main`, e nenhum código de
produção chama `save`/`balanceOf` além do próprio adaptador:

```
$ grep -rln "@Service\|@RestController" src/main/java
(nenhum)
```

Na prática, o schema e a porta funcionam, mas ninguém os usa pela aplicação ainda: quem
exercita o caminho completo é o teste de integração.

O `Ledger` em memória continua existindo e sendo usado pelos testes do domínio; ele **não**
é o caminho de persistência.

## 5. Saldo calculado em SQL — FEITO

- Continua **sem coluna de saldo**: `V1__create_ledger_schema.sql` só tem `account`,
  `transaction` e `entry`.
- O saldo é derivado em uma **única** query, em
  `JdbcLedgerRepository.balanceOf`:

```
$ grep -n "LEFT JOIN\|GROUP BY\|SUM(" src/main/java/.../ledger/infrastructure/JdbcLedgerRepository.java
81:                    SELECT COALESCE(SUM(
89:                    LEFT JOIN entry e ON e.account_id = a.id
91:                    GROUP BY a.id
```

O `account LEFT JOIN entry` devolve existência e soma no mesmo snapshot: conta inexistente
não gera linha (→ `IllegalArgumentException`, como no `Ledger` em memória) e conta sem
lançamentos gera uma linha com `0`. O `CASE` reproduz o lado normal de
`AccountType.normalSide()` em SQL, e essa duplicação é vigiada pelo teste comparativo.

Prova (teste, não inspeção): `JdbcLedgerRepositoryBalanceTest` — 9 testes: um cenário com
valor esperado escrito à mão por tipo de conta, saldo negativo, saldo zero, conta
inexistente, e a comparação do SQL com `Ledger.balanceOf` percorrendo `AccountType.values()`.

## 6. Idempotência no banco com tratamento de concorrência — NÃO FEITO

**Existe apenas a constraint, não a funcionalidade:** `transaction.idempotency_key
TEXT NOT NULL UNIQUE` em `V1__create_ledger_schema.sql`. Um `INSERT` duplicado falharia com
SQLState `23505` — mas nada faz esse `INSERT`.

**Não feito:**

- Nenhum código de aplicação faz `INSERT` em `transaction`, então a `UNIQUE` nunca é
  usada em produção — só existiria em teste.
- Não há tradução de `23505` para `PostResult.DUPLICATE`; a idempotência real do sistema
  é o `HashMap postedByKey` do `Ledger` em memória. O banco vira fonte de verdade quando o
  post fizer `INSERT` e tratar a violação de `UNIQUE` dentro de uma transação — próximo PR,
  não este.
- **Não existe teste com duas threads.** Nenhuma ocorrência em `src/`:

```
$ grep -rn "Thread\|Executor\|synchronized\|Concurrent" src/
(sem saída)
```

- O `Ledger` em memória tem check-then-act (`postedByKey.get` → ... → `put`) sem lock:
  duas threads concorrentes podem ambas retornar `POSTED` para a mesma chave, e o saldo
  pode ser contado duas vezes.

## 7. API REST — NÃO FEITO (sem endpoints de domínio)

Não existe `@RestController`, `@Controller`, `@RequestMapping`, `@GetMapping` nem
`@PostMapping` em `src/main` (grep sem saída).

Verificado subindo o jar contra um Postgres 17 (`application.yaml` não tem datasource;
foi preciso passar `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`):

| Endpoint | Resultado |
|----------|-----------|
| `GET /actuator/health` | `200 {"groups":["liveness","readiness"],"status":"UP"}` |
| `GET /actuator` | `200` |
| `GET /v3/api-docs` | `200`, porém `"paths": {}` |
| `GET /swagger-ui.html` | `302` (redirect do springdoc) |
| `GET /api/accounts`, `/api/transactions`, `/transactions`, `/ledger` | `404` |

Ou seja: **não há API de contas nem de lançamentos**. O OpenAPI está vazio, o que confirma
que não há handler nenhum. O que existe é o que o Spring Boot Actuator e o springdoc
auto-configuram.

## 8. CI e proteção da `main` — PARCIAL

**CI: FEITO.** `.github/workflows/ci.yml`:

- dispara em `pull_request` para `main` e em `push` para `main`;
- `permissions: contents: read`;
- `concurrency` com `cancel-in-progress: true`;
- `ubuntu-latest`, JDK 25 Temurin (`actions/setup-java@v4`, cache Maven),
  `actions/checkout@v4`;
- único passo de build: `./mvnw -B verify`.

Verificado pela API pública do GitHub: 8 execuções registradas; a última em `main`
(`2026-11-17T17:14:25Z`, evento `push`) terminou `success`.

**Proteção da `main`: não verificável aqui.** `gh` não está instalado no ambiente e a API
de branch protection responde `401` sem token. O repositório não guarda essa configuração.

## 9. PgVectorStore / JPA — NÃO FEITO (dependências sem uso)

Hoje ambos existem **só como auto-configuração**, sem código que os use:

**JPA**

- `spring-boot-starter-data-jpa` está no `pom.xml`, e o Hibernate inicializa
  (`Initialized JPA EntityManagerFactory for persistence unit 'default'`,
  Hibernate ORM `7.4.13.Final`).
- **Não existe nenhuma `@Entity`** no projeto (`grep -rniE "entity|jpa" src/main` sem
  resultado fora de imports de teste), então o `EntityManagerFactory` não mapeia nada.
- `application.yaml` não configura `ddl-auto`; o Hibernate não cria nem valida schema.
  O schema é 100% Flyway. Ou seja: JPA hoje é só peso no classpath e tempo de boot.

**PgVectorStore**

- `spring-ai-starter-vector-store-pgvector`, `spring-ai-vector-store-advisor` e
  `spring-ai-starter-model-ollama` estão no `pom.xml`.
- No boot aparece `o.s.a.v.pgvector.PgVectorStore : Initializing PGVectorStore schema for
  table: vector_store in schema: public`.
- Mas **nenhum código injeta `VectorStore`**, e a tabela `vector_store` **não é criada**
  no banco: depois do boot, `pg_tables` lista apenas `account`, `transaction`, `entry` e
  `flyway_schema_history`.
- Nenhuma chamada a Ollama/embeddings no código. A dependência de IA não tem uso hoje.

Conclusão: **PgVectorStore e JPA não servem para nada no projeto atual.** Não há busca
semântica, nem entidade, nem repositório JPA.

---

## Resultado do build

Comando executado na raiz, com Docker disponível (Testcontainers sobe `postgres:17`):

```
$ ./mvnw -B verify
...
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in ...LedgerSchemaTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in ...JdbcLedgerRepositoryBalanceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in ...JdbcLedgerRepositoryTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ...LedgerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ...TransactionTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ...LedgerlensApplicationTests
[INFO] Results:
[INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**33 testes, 0 falhas, BUILD SUCCESS.** (O tempo total varia com o cache da imagem
`postgres:17`.)

O `LedgerSchemaTest` (8 testes), que antes só existia como relatório órfão em
`target/surefire-reports`, está versionado e roda no `verify`. Os outros 25 somam os
testes de persistência (6 + 9) e os de domínio (5 + 4 + 1).

## Limitações conhecidas

1. **A persistência existe, mas nada em produção a chama.** O schema do Postgres está
   ligado ao código pela porta `ledger/domain/LedgerRepository` e pelo adaptador
   `ledger/infrastructure/JdbcLedgerRepository` (`JdbcClient`). O `Ledger` em memória ainda
   é usado pelos testes do domínio e **não** é o caminho de persistência. O que falta não é
   gravar/ler do banco — está provado por teste de integração —, e sim **expor isso pela
   aplicação**: não há `@Service` nem `@RestController` chamando a porta. Não há caminho de
   escrita exposto pela aplicação, então não há dado de produção a perder.
2. **`Ledger` não é thread-safe.** `HashMap`/`ArrayList` sem `synchronized`/lock; o
   check-then-act da idempotência tem corrida. Não há teste com duas threads.
3. **Idempotência ignora a descrição** (e `occurredAt` e `id`). A "mesma transação" é
   definida só pelo multiconjunto de `(conta, direção, valor)`. Duas transferências
   legítimas de mesmo valor entre as mesmas contas colidem.
4. **Sem estorno (reversal).** Não há operação, tipo de lançamento nem coluna que ligue um
   lançamento ao original. Corrigir um lançamento errado hoje exige lançar o inverso "na mão".
5. **Sem API REST** de contas/lançamentos; só Actuator e um OpenAPI vazio.
6. **Saldo é calculado em SQL, mas só por teste de integração até aqui.**
   `JdbcLedgerRepository.balanceOf` deriva o saldo em uma única query
   (`account LEFT JOIN entry ... GROUP BY a.id`), sem coluna de saldo, e o índice
   `entry_account_id_idx` serve a essa soma. A paridade com `Ledger.balanceOf` é provada
   nos cinco tipos de conta. O que ainda não existe é um endpoint ou serviço que responda
   "qual é o saldo desta conta?" para fora da aplicação.
7. **Banco não valida as regras do domínio.** Não há trigger/constraint que obrigue
   `soma(débitos) = soma(créditos)` nem "mínimo de 2 lançamentos" por transação. A
   `UNIQUE` de idempotência **é** consumida: `JdbcLedgerRepository.save` trata a violação
   `23505` e a traduz para `PostResult.DUPLICATE`. O que fica pendente para a Etapa 3:
   - (a) o `save` **não compara o conteúdo** da transação. A mesma chave com lançamentos
     diferentes vira `DUPLICATE` em silêncio, em vez de erro de chave reutilizada.
   - (b) **não existe teste de concorrência no nível do banco**: dois `save` simultâneos
     com a mesma chave não são exercitados por nenhum teste.
8. **Sem proteção de `main` verificável**, e nenhum teste de concorrência/idempotência
   no nível do banco.
9. **`java -jar` não sobe sozinho:** `application.yaml` não define datasource, então o
   boot falha com *"Failed to configure a DataSource: 'url' attribute is not specified"*.
   É preciso passar as propriedades de conexão.
10. **Dependências sem uso:** JPA (sem `@Entity`), PgVectorStore/Spring AI (sem
    `VectorStore` injetado, `vector_store` nunca criada), Lombok (nenhuma anotação usada) e
    springdoc (OpenAPI vazio). Isso infla o boot e confunde o escopo do projeto. Há ainda um
    `import java.math.BigDecimal` sem uso em `Account.java`.
11. **Divergência entre commit e conteúdo:** o commit `cdffa99` afirma cobrir as constraints
    com testes JDBC, mas o diff só traz a migration — os testes não estão versionados.
12. **Sem checagem de moeda:** `Transaction`/`Entry` não validam que as contas envolvidas
    têm a mesma `currency`; `Entry` sequer carrega moeda.
13. **Saldo é leitura consistente por statement, sem isolamento explícito.**
    `JdbcLedgerRepository.balanceOf` resolve existência e soma em uma única query
    (`account LEFT JOIN entry ... GROUP BY`), então não há janela entre duas leituras.
    Ainda assim, cada chamada enxerga o snapshot do seu próprio statement: uma transação
    commitada entre duas chamadas aparece na segunda e não na primeira, e não há
    `REPEATABLE READ` nem transação de leitura envolvendo a operação. Isolamento explícito
    será avaliado se a API precisar de leituras múltiplas consistentes entre si (por
    exemplo, saldo + extrato na mesma resposta).

---

# Inspeção de 2026-11-19 — etapa 3 concluída

Segunda inspeção, após a etapa 3. A seção anterior permanece como registro do estado em
2026-11-17; **nada acima foi reescrito**. O que mudou está aqui.

Base: `main` em `aefa5eb` (PRs #12 e #13).

## Resumo

| # | Item | Estado em 17/11 | Estado em 19/11 |
|---|------|-----------------|-----------------|
| 1 | Domínio em Java puro | FEITO | **FEITO** |
| 2 | Regras do domínio | FEITO (memória) | **FEITO** — em memória e no banco |
| 3 | Schema Flyway + testes das constraints | FEITO | **FEITO** — 8 testes, inalterado |
| 4 | Repositórios JDBC | FEITO | **FEITO** — inalterado |
| 5 | Saldo calculado em SQL | FEITO | **FEITO** — inalterado |
| 6 | Idempotência no banco com concorrência | NÃO FEITO | **CONCLUÍDA** |
| 7 | API REST | NÃO FEITO | **PENDENTE** (inalterado) |
| 8 | CI e proteção da `main` | PARCIAL | **PARCIAL** (inalterado) |
| 9 | PgVectorStore / JPA | NÃO FEITO | **RESOLVIDO POR REMOÇÃO** — não estão mais no `pom.xml` |

## 6. Idempotência no banco — CONCLUÍDA

A afirmação de 17/11 ("nenhum código faz INSERT em `transaction`", "sem teste com duas
threads") deixou de valer. O que existe agora:

- **`JdbcLedgerRepository.save`** grava cabeçalho + lançamentos em um único
  `TransactionTemplate` (tudo ou nada) e decide idempotência pela constraint.
- **`INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`** é o mecanismo. Não há pré-consulta
  (`exists` + `save`), nem `catch (DuplicateKeyException)`.
- **`0` linhas afetadas** significa chave já existente → `classifyExisting` devolve
  `PostResult.DUPLICATE` ou lança `IdempotencyConflictException`.
- **Colisão de PK não é confundida** com idempotência: a constraint é nomeada no `ON CONFLICT`,
  então `transaction_pkey` sobe como `DuplicateKeyException`.

### Por que o mecanismo importa (desvio corrigido)

A primeira implementação da etapa 3 usava `INSERT` puro + `catch (DuplicateKeyException)` e
consultava a chave depois do rollback. Funcionava quando `save` abria a própria transação, mas
quebrava quando chamado de dentro do `@Transactional` de um caso de uso: no Postgres, violar a
`UNIQUE` aborta a **transação inteira** (`SQLState 25P02`), e a consulta de recuperação não podia
mais rodar.

Evidência (teste real, antes da correção):

```
retryWithSameKeyInsideCallerTransactionReturnsDuplicate
  » UncategorizedSQL ... SQL state [25P02];
    ERROR: current transaction is aborted, commands ignored until end of transaction block
```

Depois da correção o mesmo teste devolve `DUPLICATE` e a transação do chamador continua
utilizável.

### Testes que passaram a existir

| Teste | Prova |
|-------|-------|
| `retryWithSameKeyInsideCallerTransactionReturnsDuplicate` | `DUPLICATE` dentro da transação do chamador, sem `25P02` |
| `conflictInsideCallerTransactionCommitsNothing` | conflito marca rollback-only; efeito colateral anterior não commita |
| `concurrentSameKeyYieldsExactlyOnePostedRepeatedly` | 20 rodadas × 4 threads: 1 `POSTED` + 3 `DUPLICATE` |
| `concurrentSavesWithSameKeyYieldExactlyOnePostedAndOneDuplicate` | asserta o resultado de cada thread (antes só "sem exceção") |
| `LedgerTest.concurrentPostsOfSameKeyProduceExactlyOnePosted` | `Ledger` em memória: 50 rodadas × 4 threads, saldo contado uma vez |

## `Ledger` em memória — concorrência corrigida

A limitação registrada em 17/11 ("não é thread-safe", check-then-act sem lock) foi corrigida:
`register`, `post` e `balanceOf` são `synchronized` no mesmo monitor, e as coleções passaram a
ser `LinkedHashMap`. Nenhuma regra de domínio mudou.

Antes: 4 threads postando a mesma chave em 200 rodadas → **60 rodadas inconsistentes**
(`POSTED=4`, saldo 30.000 para um lançamento de 10.000).
Depois: **0/200**.

## Resultado do build

```
$ ./mvnw -B verify
Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Distribuição: `LedgerSchemaTest` 8, `JdbcLedgerRepositoryTest` 11,
`JdbcLedgerRepositoryBalanceTest` 9, `JdbcLedgerRepositoryConcurrencyTest` 2,
`LedgerTest` 6, `TransactionTest` 4, `LedgerlensApplicationTests` 1.

O número "33 testes" citado acima era o total de 17/11; a soma citada (31) já não fechava.

## Limitações que permanecem (confirmadas em 19/11)

Corrigidas como consequência do item 6: teste de concorrência no nível do banco;
tradução da duplicidade para `DUPLICATE`; `Ledger` thread-safe.

**Ainda abertas**, e não são regressão — já estavam registradas acima:

1. **Sem API REST** e sem camada `application` (item 7 continua PENDENTE).
2. **Sem porta/adaptador para `Account`**: não há como criar conta pela aplicação.
3. **Lançamentos mutáveis no banco**: `UPDATE`/`DELETE` em `entry` são aceitos; não há
   `REVOKE` nem trigger. A imutabilidade existe só no modelo Java.
4. **Banco não valida balanceamento nem moeda**: aceita transação desbalanceada e mistura de
   moedas na mesma transação.
5. **Bordas do domínio**: overflow de `long` permite transação desbalanceada "fechar"; 
   `direction = null` é contado como crédito; `Entry.id` repetido não é checado.
   *(Dívida conhecida a resolver, não decisão permanente: `Entry.id` segue sem validação de
   nulidade nem de duplicidade, enquanto `Transaction.id` já é validado (`0983b3e`). A
   assimetria deve ser eliminada; nenhuma data foi decidida.)*
6. **Sem estorno** e sem vínculo com o lançamento original.
7. **`idempotency_key` global**, sem escopo e sem `CHECK` de tamanho.
8. **Leitura header + entries em dois statements**, sem transação de leitura.
9. **`application.yaml` sem datasource**; `springdoc` sem endpoints.

## Documentação

`README-LEDGER-LENS.md` foi removido nesta data: era uma variante antiga do `README.md`
(afirmava "se um dia existirem adaptadores" quando o adaptador JDBC já existia). O
`README.md` é o único documento vivo do estado corrente.

---

# Inspeção de 2026-11-25 — etapa 4 concluída (API REST)

A inspeção de 19/11 encerrou com "sem API REST e sem camada `application`". Nesta data as duas
lacunas foram fechadas: existe `@Service` para cada caso de uso, `@RestController` para cada
recurso, e a tradução de exceção para status fica num `@RestControllerAdvice`.

## O que passou a existir

| Endpoint | Camadas | Status |
|----------|---------|--------|
| `POST /accounts` | `CreateAccountService` → `AccountRepository` (`ON CONFLICT` na `V2`) | `201` + `Location: /accounts/{id}`, `409`, `400` |
| `GET /accounts` | `AccountQueryService.findAll` → `JdbcAccountRepository.findAll` | `200`, lista vazia é `[]` |
| `GET /accounts/{id}` | `AccountQueryService.findById` (porta devolve `Optional`) | `200`, `404`, `400` |
| `GET /accounts/{id}/balance` | `AccountBalanceService.balanceOf` (`OptionalLong`) | `200`, `404`, `400` |
| `POST /transactions` | `PostTransactionService` → `JdbcLedgerRepository` | `201`, `200`, `409`, `422`, `400` |

Erros seguem RFC 9457 (`application/problem+json`) com `title`/`detail`/`instance`, e
`AccountsNotFoundException` acrescenta `missingAccountIds`.

## Itens 1, 2 e 7 da inspeção anterior

1. **Sem API REST e sem `application` — resolvido.** `@Transactional` vive nos serviços, não no
   controller nem no adaptador. `AccountController`, `TransactionController` e
   `ApiExceptionHandler` em `*/api`; `CreateAccountService`, `AccountQueryService`,
   `AccountBalanceService`, `PostTransactionService` em `*/application`.
2. **Sem porta/adaptador para `Account` — resolvido.** `AccountRepository` (domínio) com
   `save`, `findAllByIds`, `findById`, `findAll`; `JdbcAccountRepository` implementa.
3. **Sem endpoints de domínio no OpenAPI — resolvido.** `/v3/api-docs` lista os quatro paths.

## O que continua aberto

Os itens 3 a 9 da inspeção de 19/11 seguem abertos, sem regressão: lançamentos mutáveis no
banco, banco sem validar balanceamento/moeda, bordas de `long`/`null`, ausência de estorno,
`idempotency_key` global, leitura em dois statements.

**`Entry.id` repetido continua sem validação** — a assimetria com `Transaction.id` não foi
tratada nesta etapa e permanece como dívida conhecida, não como decisão. Nenhuma data foi
decidida.

Duas limitações nasceram com a API e estão registradas no `README`:

- `GET /accounts` não pagina: devolve a tabela inteira.
- Não há `GET /transactions`: transação só é relida pela chave de idempotência.
- Não há autenticação: qualquer cliente que alcance a porta escreve no razão.

## Resultado do build

```
$ ./mvnw -B verify
Tests run: 127, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Distribuição: `AccountControllerTest` 13, `JdbcLedgerRepositoryTest` 13,
`JdbcAccountRepositoryTest` 12, `PostTransactionServiceTest` 10, `LedgerSchemaTest` 10,
`TransactionControllerTest` 9, `JdbcLedgerRepositoryBalanceTest` 9, `LedgerTest` 7,
`IdempotencyContentTest` 7, `ApiEndToEndTest` 7, `AccountTest` 7, `TransactionTest` 6,
`CreateAccountCommandTest` 4, `AccountQueryServiceTest` 4, `AccountBalanceServiceTest` 3,
`AccountBalanceServiceErrorTest` 3, `JdbcLedgerRepositoryConcurrencyTest` 2,
`LedgerlensApplicationTests` 1.

Além da suíte, um fluxo `curl` de 18 passos foi executado contra o jar e um Postgres 17 reais
(transcrito no `README`, seção 3): todos os passos responderam o status esperado.

---

# Inspeção de 2026-11-27 — etapa 4, `GET /accounts` (branch `feat/etapa-4-get-accounts`)

Inspeção da branch `feat/etapa-4-get-accounts`, que adiciona `GET /accounts` e
`GET /accounts/{id}` sobre a etapa 4. Base: `origin/main` em `ac102ed`. Nada acima foi
reescrito.

## O que mudou

| Item | Antes | Agora |
|------|-------|-------|
| `Location` do `POST /accounts` | `/accounts/{id}/balance` (não existia `GET /accounts/{id}`) | `/accounts/{id}`, seguível pelo cliente |
| `GET /accounts` | não existia | `200` com a lista ordenada por `name, currency`; vazio é `[]` |
| `GET /accounts/{id}` | não existia | `200`; `404` conta inexistente; `400` id fora de UUID |
| Ordem do `findAll` | não fixada por teste | `ORDER BY name, currency`, provado inserindo fora de ordem |
| E2E do `Location` | só comparava o header ao id do corpo | segue o header com `GET` e confere `id`/`name`/`currency`/`type` |
| `500` | documentado só no javadoc do `ApiExceptionHandler` | linha na tabela do README, com prova ponta a ponta de que a resposta não vaza detalhe interno |

O `500` não é um handler nosso: `IllegalArgumentException` genérico continua sem mapeamento de
propósito. O corpo observado no teste foi
`{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/accounts/..."}`, sem
`detail`, sem mensagem e sem stack trace.

## O que continua aberto

- `GET /accounts` não pagina.
- Não há `GET /transactions`.
- Sem autenticação.
- **Dívida: o teste do `500` depende do schema não validar ISO 4217.**
  `ApiEndToEndTest.unexpectedErrorIs500WithoutLeakingInternals` força o caminho de erro gravando
  uma conta com `currency = 'brl'` direto no banco, porque a coluna é `CHAR(3)` sem `CHECK` de
  formato e só o construtor de `Account` recusa a moeda. Se um dia o schema ganhar
  `CHECK (currency ~ '[A-Z]{3}')` (item já listado como correção de integridade), o `INSERT` do
  teste passa a falhar com violação de constraint e o gatilho do `500` precisa mudar. Gatilho
  acordado em 27/11: o teste será reescrito para injetar falha no adaptador — e não afrouxado.
  Não é urgente, mas o teste não pode ser "consertado" afrouxando os asserts: a garantia de que a
  resposta não vaza detalhe interno é o ponto dele.
- `docs/STATUS.md` é histórico por decisão: a seção de 25/11 continua com a contagem daquele dia
  (127); a contagem corrente é a desta seção.

## Resultado do build

```
$ ./mvnw -B verify
Tests run: 129, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Distribuição: `AccountControllerTest` 13, `JdbcAccountRepositoryTest` 13,
`JdbcLedgerRepositoryTest` 13, `PostTransactionServiceTest` 10, `LedgerSchemaTest` 10,
`TransactionControllerTest` 9, `JdbcLedgerRepositoryBalanceTest` 9, `ApiEndToEndTest` 8,
`LedgerTest` 7, `IdempotencyContentTest` 7, `AccountTest` 7, `TransactionTest` 6,
`AccountQueryServiceTest` 4, `CreateAccountCommandTest` 4, `AccountBalanceServiceTest` 3,
`AccountBalanceServiceErrorTest` 3, `JdbcLedgerRepositoryConcurrencyTest` 2,
`LedgerlensApplicationTests` 1.

A soma bate: 129. Frente à inspeção de 25/11 (127), entraram
`JdbcAccountRepositoryTest.listsAccountsByNameThenCurrency` e
`ApiEndToEndTest.unexpectedErrorIs500WithoutLeakingInternals`, e o e2e do `Location` passou a
seguir o header.
