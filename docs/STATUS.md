# Estado atual do ledgerlens

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
   aplicação**: não há `@Service` nem `@RestController` chamando a porta, então reiniciar o
   processo nunca perde dados de uso real simplesmente porque não há uso real ainda.
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
   `UNIQUE` de idempotência existe, mas nada a consome.
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
