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

> Estado: o **domínio** está implementado e testado; a **persistência e a API** ainda não.
> O resumo honesto está na seção 5 e nos detalhes de [`docs/STATUS.md`](docs/STATUS.md).

## 2. Princípios de design

1. **Saldo é calculado, nunca guardado.** `Ledger.balanceOf(accountId)` percorre os lançamentos
   e soma/subtrai conforme o lado normal do tipo de conta (`ASSET`/`EXPENSE` → débito;
   `LIABILITY`/`EQUITY`/`REVENUE` → crédito). Não existe coluna de saldo no schema.
2. **Lançamentos são imutáveis.** `Transaction` e `Entry` são `record`, e `Transaction` faz
   `List.copyOf(entries)`. Postar não altera nem apaga lançamento anterior.
3. **Toda transação fecha.** O construtor de `Transaction` rejeita a transação se
   `soma(débitos) != soma(créditos)`, e exige mínimo de 2 lançamentos.
4. **Idempotência.** Postar duas vezes com a mesma `idempotencyKey` e o mesmo conteúdo retorna
   `PostResult.DUPLICATE` sem duplicar o efeito; a mesma chave com conteúdo diferente lança
   `IllegalArgumentException`.
5. **Dinheiro em `long` de centavos.** `Entry.amountCents` é `long` e precisa ser `> 0`.
   Nunca `double`/`float`. No banco, `amount_cents BIGINT` com `CHECK (amount_cents > 0)`.

## 3. Como rodar e testar

Pré-requisitos reais do que existe hoje:

- JDK 25 (o `pom.xml` fixa `java.version=25`).
- Docker rodando — os testes de contexto sobem um Postgres 17 via Testcontainers.
- Não é preciso ter Maven instalado: use o wrapper (`./mvnw`).

### Testar (comando executado)

```bash
./mvnw -B verify
```

Resultado nesta inspeção: **10 testes, 0 falhas, `BUILD SUCCESS`**.

### Rodar a aplicação

`src/main/resources/application.yaml` **não** define datasource, então `java -jar` sozinho
falha com `Failed to configure a DataSource: 'url' attribute is not specified`. É preciso
apontar para um Postgres. O comando que funcionou:

```bash
# 1) suba um Postgres 17 (o Flyway aplica V1 sozinho no boot)
docker run -d --rm --name ll-pg -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=ll -p 55432:5432 postgres:17

# 2) empacote e suba a aplicação apontando para ele
./mvnw -B -DskipTests package
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/ll \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=pw \
java -jar target/ledgerlens-0.0.1-SNAPSHOT.jar
```

A aplicação sobe em `http://localhost:8080`. O que responde hoje:

| Endpoint | Resposta |
|----------|----------|
| `GET /actuator/health` | `{"status":"UP"}` |
| `GET /v3/api-docs` | `200`, com `"paths": {}` |
| `GET /swagger-ui.html` | `302` |
| `/api/accounts`, `/api/transactions` | `404` (não existem) |

## 4. Estrutura de packages

```
com.github.devnic0las.ledgerlens
├── account/domain          Account, AccountType
├── ledger/domain           Ledger, Transaction, Entry, Direction, PostResult
└── LedgerlensApplication   bootstrap Spring Boot (único ponto com Spring no main)
```

**Regra de dependência:** a camada de domínio não conhece Spring, SQL nem persistência.
Os subpacotes `account/domain` e `ledger/domain` não têm nenhum import de
`org.springframework`, nenhuma chamada `JdbcClient`/`sql(...)` e nenhum SQL literal
(verificado por grep com escopo em `*/domain/`).

O fluxo de dependência é sempre para dentro:

- **`application` depende só do domínio.** Orquestra casos de uso chamando as portas do
  domínio; não conhece JDBC, REST, `DataSource` nem `TransactionTemplate`.
- **`infrastructure` depende do domínio e implementa as portas.** É onde vivem os
  adaptadores (JDBC, REST), o SQL e o Spring de persistência — por exemplo
  `ledger/infrastructure/JdbcLedgerRepository` implementa `ledger/domain/LedgerRepository`.
- **`application` nunca importa `infrastructure`.** Quem liga as duas pontas é o Spring,
  no bootstrap (`LedgerlensApplication`), não o caso de uso.

## 5. Estado atual

Fonte: [`docs/STATUS.md`](docs/STATUS.md), sem maquiagem.

| # | Item | Estado |
|---|------|--------|
| 1 | Domínio em Java puro (`account/domain` e `ledger/domain` sem Spring, SQL ou persistência) | **FEITO** |
| 2 | Regras: valor positivo, mínimo 2 lançamentos, débitos = créditos, saldo calculado, idempotência com checagem de conteúdo | **FEITO** (em memória) |
| 3 | Schema Flyway e testes das constraints | **PARCIAL** — migration existe; os testes JDBC não estão versionados |
| 4 | Repositórios JDBC (Ledger grava/lê do Postgres) | **NÃO FEITO** |
| 5 | Saldo calculado em SQL | **NÃO FEITO** |
| 6 | Idempotência no banco com concorrência | **NÃO FEITO** — só a `UNIQUE` existe; nada a consome, sem teste com duas threads |
| 7 | API REST | **NÃO FEITO** — só Actuator/OpenAPI vazio |
| 8 | CI e proteção da `main` | **PARCIAL** — CI roda `./mvnw -B verify`; proteção não verificável aqui |
| 9 | PgVectorStore / JPA | **NÃO FEITO** — dependências sem uso |

O que funciona hoje: as regras contábeis e a idempotência **dentro de um processo**, em
memória, cobertas por 10 testes. O que não funciona: nada disso é persistido, não há endpoint
de domínio e o banco não é usado pelo código Java.

## 6. Decisões e limitações

Decisões registradas que afetam o comportamento:

- **Idempotência compara conta, direção e valor — e ignora a descrição.** `Ledger.sameContent`
  reduz cada lançamento a `(accountId, direction, amountCents)` e compara os multiconjuntos.
  `description`, `occurredAt` e `id` da transação são ignorados. Consequência: repetir a chave
  com a mesma conta/direção/valor mas descrição diferente é tratado como `DUPLICATE`, não como
  erro.
- **`PostResult` só tem `POSTED` e `DUPLICATE`.** Conteúdo divergente na mesma chave não é um
  resultado: é exceção.
- **A validação acontece toda no construtor do domínio**, para que seja impossível construir
  uma transação inválida — inclusive vinda de um futuro adaptador.
- **O schema não duplica as regras do domínio.** O banco garante `amount_cents > 0`,
  `direction ∈ {DEBIT, CREDIT}`, `type` conhecido, FKs e unicidade da chave. Não há trigger
  que force débitos = créditos.

Limitações conhecidas:

- **Estorno ainda não implementado.** Não há operação de reversal, nem vínculo entre um
  lançamento e seu original. Corrigir exige lançar o inverso manualmente.
- **O `Ledger` em memória não é thread-safe.** `HashMap`/`ArrayList` sem lock; o
  check-then-act da idempotência tem corrida, então duas threads podem postar a mesma chave
  e ambas receberem `POSTED`.
- **Sem persistência:** reiniciar o processo perde contas e lançamentos. O schema Postgres
  existe, mas nenhum código Java o usa.
- **Sem saldo em SQL:** não há `SUM`/`GROUP BY`; o índice `entry_account_id_idx` foi criado
  para uma query que ainda não existe.
- **Sem API REST de domínio** e sem checagem de que as contas de uma transação compartilham a
  mesma moeda.
- **`application.yaml` não configura datasource**: rodar exige variáveis de ambiente.
- **Dependências sem uso** (JPA, PgVectorStore/Spring AI, Lombok, springdoc) aumentam o boot
  sem entregar funcionalidade.
- **O commit `cdffa99` diz cobrir as constraints com testes JDBC, mas só adiciona a
  migration.** `LedgerSchemaTest.java` não está no repositório; existe apenas um relatório
  órfão em `target/surefire-reports`, que não roda.

## 7. Próximos passos

Somente o que está **PARCIAL** ou **NÃO FEITO** no STATUS — nada além disso:

1. **Versionar os testes de constraints** do schema (`LedgerSchemaTest`), restaurando o
   estado "FEITO" do item 3.
2. **Implementar repositórios JDBC** que gravem e leiam `account`, `transaction` e `entry`,
   ligando o domínio ao Postgres (item 4).
3. **Calcular o saldo em SQL** (`SUM` por conta respeitando o lado normal), usando o índice
   já criado (item 5).
4. **Idempotência no banco:** traduzir o `23505` da `UNIQUE` para `DUPLICATE` e adicionar teste
   com duas threads concorrentes (item 6).
5. **Expor a API REST** de contas e lançamentos, hoje inexistente (item 7).
6. **Decidir o destino de JPA/PgVectorStore** — remover ou justificar com código real (item 9).
7. **Configurar datasource padrão** e documentar a proteção de branch da `main`, hoje não
   verificável a partir do repositório (item 8).
