package com.github.devnic0las.ledgerlens.ledger.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Identidade de uma transação para fins de idempotência: a descrição mais o multiconjunto de
 * {@code (conta, direção, valor)} de seus lançamentos.
 *
 * <p>{@code id} e {@code occurredAt} ficam de fora de propósito. São gerados pelo servidor a
 * cada tentativa; incluí-los faria todo retry legítimo — mesma chave, mesmo pedido — ser lido
 * como conteúdo diferente. A identidade é a do pedido, não a da linha gravada.
 */
public final class IdempotencyContent {

  private IdempotencyContent() {}

  public static boolean matches(Transaction a, Transaction b) {
    return a.description().equals(b.description())
            && shapesOf(a).equals(shapesOf(b));
  }

  private static Map<EntryShape, Long> shapesOf(Transaction transaction) {
    Map<EntryShape, Long> shapes = new HashMap<>();

    for (Entry entry : transaction.entries()) {
      var shape = new EntryShape(entry.accountId(), entry.direction(), entry.amountCents());
      shapes.merge(shape, 1L, Long::sum);
    }

    return shapes;
  }

  private record EntryShape(UUID accountId, Direction direction, long amountCents) {}
}
