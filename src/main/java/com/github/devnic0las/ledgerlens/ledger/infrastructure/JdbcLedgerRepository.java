package com.github.devnic0las.ledgerlens.ledger.infrastructure;

import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import org.springframework.dao.DuplicateKeyException;
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
    try {
      transactionTemplate.executeWithoutResult(status -> insert(transaction));
      return PostResult.POSTED;
    } catch (DuplicateKeyException duplicate) {
      // O banco decide a idempotência: duas requisições com a mesma chave disputam a
      // UNIQUE, uma grava e a outra recebe 23505 com a transação inteira desfeita.
      // Só o conflito de idempotency_key é esperado; colisão de id (PK) continua subindo.
      // Consultar a chave depois do rollback evita depender do nome da constraint.
      if (findByIdempotencyKey(transaction.idempotencyKey()).isPresent()) {
        return PostResult.DUPLICATE;
      }
      throw duplicate;
    }
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
   * <p>O {@code CASE} repete o lado normal de {@link AccountType#normalSide()} em SQL,
   * porque o banco não tem os enums do domínio. Essa duplicação é intencional e é
   * vigiada pelo teste que compara os dois cálculos nos cinco tipos de conta.
   *
   * <p>A existência da conta é checada antes da soma: conta inexistente lança, conta
   * existente sem lançamentos devolve 0. Sem essa checagem, as duas situações dariam 0.
   */
  @Override
  public long balanceOf(UUID accountId) {
    var type = jdbc.sql("SELECT type FROM account WHERE id = :id")
            .param("id", accountId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

    var normalSide = AccountType.valueOf(type).normalSide();

    return jdbc.sql("""
                    SELECT COALESCE(SUM(
                               CASE
                                   WHEN :normalSide = 'DEBIT' AND e.direction = 'DEBIT' THEN e.amount_cents
                                   WHEN :normalSide = 'DEBIT' AND e.direction = 'CREDIT' THEN -e.amount_cents
                                   WHEN :normalSide = 'CREDIT' AND e.direction = 'CREDIT' THEN e.amount_cents
                                   ELSE -e.amount_cents
                               END), 0)
                    FROM entry e
                    WHERE e.account_id = :accountId
                    """)
            .param("normalSide", normalSide.name())
            .param("accountId", accountId)
            .query(Long.class)
            .single();
  }

  private void insert(Transaction transaction) {
    jdbc.sql("""
                    INSERT INTO transaction (id, occurred_at, description, idempotency_key)
                    VALUES (:id, :occurredAt, :description, :idempotencyKey)
                    """)
            .param("id", transaction.id())
            .param("occurredAt", Timestamp.from(transaction.occurredAt()))
            .param("description", transaction.description())
            .param("idempotencyKey", transaction.idempotencyKey())
            .update();

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
