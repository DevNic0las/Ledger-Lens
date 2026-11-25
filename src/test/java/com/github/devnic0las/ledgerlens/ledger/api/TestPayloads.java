package com.github.devnic0las.ledgerlens.ledger.api;

import com.github.devnic0las.ledgerlens.ledger.application.PostTransactionResult;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import com.github.devnic0las.ledgerlens.ledger.domain.Entry;
import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Corpos compartilhados pelos testes de borda, para não repetir um `Transaction` em cada um. */
final class TestPayloads {

  static final UUID ORIGINAL_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  private TestPayloads() {}

  static PostTransactionResult posted() {
    return new PostTransactionResult(transaction(), PostResult.POSTED);
  }

  static PostTransactionResult duplicate() {
    return new PostTransactionResult(transaction(), PostResult.DUPLICATE);
  }

  private static Transaction transaction() {
    return new Transaction(
            ORIGINAL_ID,
            Instant.parse("2026-11-24T10:00:00Z"),
            "Pix",
            "chave-1",
            List.of(
                    new Entry(UUID.randomUUID(),
                            UUID.fromString("11111111-1111-1111-1111-111111111111"),
                            Direction.DEBIT, 10_000),
                    new Entry(UUID.randomUUID(),
                            UUID.fromString("22222222-2222-2222-2222-222222222222"),
                            Direction.CREDIT, 10_000)));
  }
}
