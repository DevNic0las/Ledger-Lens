package com.github.devnic0las.ledgerlens.ledger.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistência do ledger. Vive no domínio para que o domínio não conheça
 * banco, transação, Spring nem SQL. A implementação fica em infraestrutura.
 */
public interface LedgerRepository {

  /**
   * Grava a transação com todos os seus lançamentos de forma atômica.
   *
   * @return {@link PostResult#POSTED} quando gravou, {@link PostResult#DUPLICATE} quando
   *         já existia transação com a mesma {@code idempotencyKey} e nada foi alterado.
   *         Se um lançamento falhar, nada é gravado e a exceção de infraestrutura sobe.
   */
  PostResult save(Transaction transaction);

  Optional<Transaction> findById(UUID id);

  Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
