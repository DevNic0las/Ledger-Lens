# Estado atual do ledgerlens

Relatório gerado a partir da inspeção do repositório na branch `docs/readme-and-status`
(base: `main` em `cdffa99`). Nada aqui foi inferido de intenção ou de mensagem de commit:
cada afirmação tem arquivo, teste ou saída de comando como evidência.

Data da inspeção: 2026-11-17.

---

## Resumo

| # | Item | Estado |
|---|------|--------|
| 1 | Domínio em Java puro (`account/` e `ledger/`) | **FEITO** |
| 2 | Regras do domínio | **FEITO** (só em memória) |
| 3 | Schema Flyway e testes das constraints | **PARCIAL** |
| 4 | Repositórios JDBC (Ledger grava/lê do Postgres) | **NÃO FEITO** |
| 5 | Saldo calculado em SQL | **NÃO FEITO** |
| 6 | Idempotência no banco com concorrência | **PARCIAL** (só a constraint) |
| 7 | API REST | **NÃO FEITO** (existe só Actuator/OpenAPI vazio) |
| 8 | CI e proteção da `main` | **PARCIAL** (CI FEITO, proteção não verificável) |
| 9 | PgVectorStore / JPA | **NÃO FEITO** (dependências sem uso) |

---

## 1. Domínio em Java puro — FEITO

`account/` e `ledger/` não têm nenhum import de Spring, nem anotação de persistência.

Evidência (comando executado na raiz do repositório):

```
$ grep -rn "org\.springframework" src/main/java/.../account src/main/java/.../ledger
$ echo $?
1        # nenhuma ocorrência
```

Também não há `jakarta.persistence`, `@Entity` nem `@Repository` em `src/main`.

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

## 3. Schema Flyway e testes das constraints — PARCIAL

**Feito:** a migration existe e é aplicada.

- `src/main/resources/db/migration/V1__create_ledger_schema.sql` cria `account`,
  `transaction` e `entry`, com `CHECK` de `type`, `CHECK` de `direction`,
  `CHECK (amount_cents > 0)`, `UNIQUE (idempotency_key)` e duas FKs.
- Confirmado rodando a aplicação contra um Postgres 17 local: o Flyway aplicou
  `Migrating schema "public" to version "1 - create ledger schema"` e as tabelas
  `account`, `transaction`, `entry` + `flyway_schema_history` ficaram no banco.
- `LedgerlensApplicationTests.contextLoads` sobe o contexto com Testcontainers
  (`postgres:17`) e passa, o que prova que a migration roda em schema vazio.

**Não feito:** os testes das constraints **não estão no repositório**.

O commit `cdffa99` tem a mensagem *"create ledger schema with Flyway and cover
constraints with JDBC tests"*, mas o diff desse commit adiciona **somente o arquivo
`.sql`** (28 linhas). `LedgerSchemaTest.java` não está em `git ls-files`, nem na árvore de
trabalho, em nenhuma branch:

```
$ git log --all --oneline -- 'src/test/java/**/LedgerSchemaTest.java'
(sem saída)

$ git ls-files src/test/java
.../LedgerTest.java
.../LedgerlensApplicationTests.java
.../TestLedgerlensApplication.java
.../TestcontainersConfiguration.java
.../TransactionTest.java
```

Existe apenas um artefato de build órfão, de 2026-11-14 15:10, em
`target/surefire-reports/com.github.devnic0las.ledgerlens.LedgerSchemaTest.txt` dizendo
"Tests run: 8, Failures: 0". Ele é apagado no próximo `mvnw clean` e **não roda** no
`./mvnw verify` atual. Portanto, as constraints têm schema, mas **não têm prova versionada**.

## 4. Repositórios JDBC — NÃO FEITO

Não existe nenhum repositório. O `Ledger` não toca no Postgres.

```
$ grep -rn "JdbcTemplate\|DataSource\|SimpleJdbcInsert" src/main/java
(sem saída)
```

Não há `src/main/java/.../ledger/adapter`, nem `repository`, nem `*Jdbc*`. O `JdbcTemplate`
só aparece dentro do `.class` órfão citado no item 3 — não no código-fonte versionado.
`Ledger` guarda tudo em `Map`/`List` na heap do processo.

## 5. Saldo calculado em SQL — NÃO FEITO

- Não existe coluna de saldo (correto: `V1__create_ledger_schema.sql` só tem
  `account`, `transaction`, `entry`).
- Não existe nenhuma query de `SUM(...)`, nem `GROUP BY` em lugar algum.
- O único vestígio é o índice `entry_account_id_idx`, cujo comentário na migration diz
  que serve "para somar lançamentos por conta ao calcular o saldo" — mas **a query não
  existe**. O cálculo real é o laço Java em `Ledger.balanceOf`.

## 6. Idempotência no banco com tratamento de concorrência — PARCIAL

**Feito (só a constraint):** `transaction.idempotency_key TEXT NOT NULL UNIQUE`
em `V1__create_ledger_schema.sql`. Um `INSERT` duplicado falha com SQLState `23505`.

**Não feito:**

- Nenhum código de aplicação faz `INSERT` em `transaction`, então a `UNIQUE` nunca é
  usada em produção — só existiria em teste.
- Não há tradução de `23505` para `PostResult.DUPLICATE`; a idempotência real do sistema
  é o `HashMap postedByKey` do `Ledger` em memória.
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
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ...LedgerlensApplicationTests
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in ...LedgerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in ...TransactionTest
[INFO] Results:
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**10 testes, 0 falhas, BUILD SUCCESS.** (O tempo total varia com o cache da imagem
`postgres:17`: 44 s na primeira execução, ~22 s na seguinte.)

Observação: `LedgerSchemaTest` (8 testes) aparece em um relatório antigo em
`target/surefire-reports`, de 2026-11-14, mas **não é compilado nem executado** hoje
porque o arquivo-fonte não está no repositório. Se ele fosse contado, o total seria 18;
o número real e reproduzível é **10**.

## Limitações conhecidas

1. **Sem persistência.** O domínio (`Ledger`) é 100% em memória; o schema do Postgres está
   criado mas desconectado do código. Reiniciar o processo perde tudo.
2. **`Ledger` não é thread-safe.** `HashMap`/`ArrayList` sem `synchronized`/lock; o
   check-then-act da idempotência tem corrida. Não há teste com duas threads.
3. **Idempotência ignora a descrição** (e `occurredAt` e `id`). A "mesma transação" é
   definida só pelo multiconjunto de `(conta, direção, valor)`. Duas transferências
   legítimas de mesmo valor entre as mesmas contas colidem.
4. **Sem estorno (reversal).** Não há operação, tipo de lançamento nem coluna que ligue um
   lançamento ao original. Corrigir um lançamento errado hoje exige lançar o inverso "na mão".
5. **Sem API REST** de contas/lançamentos; só Actuator e um OpenAPI vazio.
6. **Sem cálculo de saldo em SQL**, apesar de o índice `entry_account_id_idx` existir para isso.
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
