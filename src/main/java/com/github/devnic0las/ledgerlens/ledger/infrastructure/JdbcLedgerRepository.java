package com.github.devnic0las.ledgerlens.ledger.infrastructure;

import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.IdempotencyContent;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Adaptador JDBC do {@link LedgerRepository}. Toda a escrita acontece dentro de uma
 * única transação de banco: a transação contábil e seus lançamentos são gravados juntos
 * ou não são gravados de forma alguma.
 */
@Repository
public class JdbcLedgerRepository implements LedgerRepository {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactionTemplate;

  public JdbcLedgerRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @Override
  public PostResult save(Transaction transaction) {
    return transactionTemplate.execute(status -> {
      // ON CONFLICT (idempotency_key) DO NOTHING: a UNIQUE da chave decide a idempotência
      // sem gerar erro. Se a chave já existir, o Postgres não grava nada e devolve 0 linhas
      // afetadas; a transação nunca é abortada (ao contrário do INSERT puro + catch de 23505,
      // que em Postgres invalida a transação inteira com SQLState 25P02).
      // Apenas a constraint de idempotency_key é alvo: colisão de PK (transaction_pkey)
      // continua subindo como DuplicateKeyException.
      int inserted = jdbc.sql("""
                      INSERT INTO transaction (id, occurred_at, description, idempotency_key)
                      VALUES (:id, :occurredAt, :description, :idempotencyKey)
                      ON CONFLICT (idempotency_key) DO NOTHING
                      """)
              .param("id", transaction.id())
              .param("occurredAt", Timestamp.from(transaction.occurredAt()))
              .param("description", transaction.description())
              .param("idempotencyKey", transaction.idempotencyKey())
              .update();

      if (inserted == 0) {
        // Nada foi gravado por esta chamada: a chave já existia. A linha vencedora já está
        // visível nesta transação porque o ON CONFLICT espera a concorrente commitar.
        return classifyExisting(transaction);
      }

      insertEntries(transaction);
      return PostResult.POSTED;
    });
  }

  /**
   * Decide o resultado de uma chave já existente. Chamado dentro da própria transação de
   * escrita, quando não houve erro de banco — por isso a leitura é segura.
   */
  private PostResult classifyExisting(Transaction transaction) {
    var existing = findByIdempotencyKey(transaction.idempotencyKey())
            .orElseThrow(() -> new IllegalStateException(
                    "ON CONFLICT DO NOTHING sem chave visível: " + transaction.idempotencyKey()));

    if (IdempotencyContent.matches(existing, transaction)) {
      return PostResult.DUPLICATE;
    }
    throw new IdempotencyConflictException(
            "Idempotency key reused with different content: " + transaction.idempotencyKey());
  }

  @Override
  public Optional<Transaction> findById(UUID id) {
    return findOne("SELECT * FROM transaction WHERE id = :id", "id", id);
  }

  @Override
  public Optional<Transaction> findByIdempotencyKey(String idempotencyKey) {
    return findOne("SELECT * FROM transaction WHERE idempotency_key = :key", "key", idempotencyKey);
  }

  /**
   * Deriva o saldo no banco, sem coluna de saldo e sem carregar lançamentos na JVM.
   *
   * <p>É uma única query de propósito: checar a existência da conta em um SELECT e somar
   * em outro abriria uma janela em que a conta existe para uma query e não para a outra.
   * O {@code LEFT JOIN} resolve as duas informações no mesmo snapshot: a conta vem do
   * lado esquerdo (logo, existe), e a soma é 0 quando não há lançamento.
   *
   * <p>O {@code CASE} repete o lado normal de {@link AccountType#normalSide()} em SQL,
   * porque o banco não tem os enums do domínio. Essa duplicação é intencional e é
   * vigiada pelo teste que compara os dois cálculos nos cinco tipos de conta.
   */
  @Override
  public OptionalLong balanceOf(UUID accountId) {
    return jdbc.sql("""
                    SELECT COALESCE(SUM(
                               CASE
                                   WHEN a.type IN ('ASSET', 'EXPENSE') AND e.direction = 'DEBIT' THEN e.amount_cents
                                   WHEN a.type IN ('ASSET', 'EXPENSE') AND e.direction = 'CREDIT' THEN -e.amount_cents
                                   WHEN a.type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND e.direction = 'CREDIT' THEN e.amount_cents
                                   WHEN a.type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND e.direction = 'DEBIT' THEN -e.amount_cents
                               END), 0)
                    FROM account a
                    LEFT JOIN entry e ON e.account_id = a.id
                    WHERE a.id = :accountId
                    GROUP BY a.id
                    """)
            .param("accountId", accountId)
            .query(Long.class)
            .optional()
            .map(OptionalLong::of)
            .orElseGet(OptionalLong::empty);
  }

  private void insertEntries(Transaction transaction) {
    for (Entry entry : transaction.entries()) {
      jdbc.sql("""
                      INSERT INTO entry (id, transaction_id, account_id, direction, amount_cents)
                      VALUES (:id, :transactionId, :accountId, :direction, :amountCents)
                      """)
              .param("id", entry.id())
              .param("transactionId", transaction.id())
              .param("accountId", entry.accountId())
              .param("direction", entry.direction().name())
              .param("amountCents", entry.amountCents())
              .update();
    }
  }

  private Optional<Transaction> findOne(String sql, String parameterName, Object parameterValue) {
    return jdbc.sql(sql)
            .param(parameterName, parameterValue)
            .query(this::mapTransactionHeader)
            .optional()
            .map(header -> header.toTransaction(entriesOf(header.id())));
  }

  private TransactionHeader mapTransactionHeader(ResultSet rs, int rowNum) throws SQLException {
    return new TransactionHeader(
            rs.getObject("id", UUID.class),
            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
            rs.getString("description"),
            rs.getString("idempotency_key"));
  }

  /**
   * V1 não tem coluna de ordenação para lançamentos: a ordem de leitura é por {@code id},
   * que é estável mas sem significado. O domínio trata lançamentos como multiconjunto
   * (a idempotência compara por conta/direção/valor), então a ordem não altera nenhuma regra.
   */
  private java.util.List<Entry> entriesOf(UUID transactionId) {
    return jdbc.sql("""
                    SELECT id, account_id, direction, amount_cents
                    FROM entry
                    WHERE transaction_id = :transactionId
                    ORDER BY id
                    """)
            .param("transactionId", transactionId)
            .query((rs, rowNum) -> new Entry(
                    rs.getObject("id", UUID.class),
                    rs.getObject("account_id", UUID.class),
                    Direction.valueOf(rs.getString("direction")),
                    rs.getLong("amount_cents")))
            .list();
  }

  private record TransactionHeader(UUID id, Instant occurredAt, String description, String idempotencyKey) {

    Transaction toTransaction(java.util.List<Entry> entries) {
      return new Transaction(id, occurredAt, description, idempotencyKey, entries);
    }
  }
}
