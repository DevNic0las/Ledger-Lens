package com.github.devnic0las.ledgerlens;

import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.InvalidEntryException;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.InvalidTransactionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


public class TransactionTest   {

  private UUID checkingAccountId;
  private UUID revenueAccountId;

  @BeforeEach
  void setUp() {
    checkingAccountId = UUID.randomUUID();
    revenueAccountId = UUID.randomUUID();
  }

  private Transaction pix(String idempotencyKey, long debitCents, long creditCents) {
    var entries = List.of(
            new Entry(UUID.randomUUID(), checkingAccountId, Direction.DEBIT, debitCents),
            new Entry(UUID.randomUUID(), revenueAccountId, Direction.CREDIT, creditCents)
    );
    return new Transaction(UUID.randomUUID(), Instant.now(), "Pix", idempotencyKey, entries);
  }

  @Test
  void shouldRejectTransactionUnbalanced() {
    var exception = assertThrows(InvalidTransactionException.class, () ->
            pix("chave-1", 10_000, 9_000)
    );
    // A exceção é específica para que a borda HTTP não precise mapear todo
    // IllegalArgumentException (o que esconderia bug de programação).
    assertEquals("Transaction is unbalanced", exception.getMessage());
  }

  @Test
  void shouldAcceptTransactionBalanced() {
    assertDoesNotThrow(() -> pix("chave-1", 10_000, 10_000));
  }

  @Test
  void shouldRejectEmptyTransaction() {
    var entries = List.<Entry>of();

    assertThrows(InvalidTransactionException.class, () ->
            new Transaction(UUID.randomUUID(), Instant.now(), "Pix", "chave-1", entries)
    );
  }

  @Test
  void shouldRejectZeroAmount() {
    assertThrows(InvalidEntryException.class, () ->
            new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.DEBIT, 0)
    );
  }

  @Test
  void shouldRejectNegativeAmount() {
    assertThrows(InvalidEntryException.class, () ->
            new Entry(UUID.randomUUID(), UUID.randomUUID(), Direction.CREDIT, -1)
    );
  }

  /**
   * Defesa em profundidade: um adaptador futuro não pode construir transação sem identidade.
   * O caminho atual nunca passa null — o serviço gera o id e a leitura do banco o traz de uma
   * coluna {@code UUID PRIMARY KEY} (logo {@code NOT NULL}).
   */
  @Test
  void shouldRejectNullId() {
    var entries = List.of(
            new Entry(UUID.randomUUID(), checkingAccountId, Direction.DEBIT, 100),
            new Entry(UUID.randomUUID(), revenueAccountId, Direction.CREDIT, 100));

    assertThrows(NullPointerException.class, () ->
            new Transaction(null, Instant.now(), "Pix", "chave-1", entries)
    );
  }

}
