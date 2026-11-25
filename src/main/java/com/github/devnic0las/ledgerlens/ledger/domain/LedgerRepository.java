package com.github.devnic0las.ledgerlens.ledger.domain;

import com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException;

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
   * <p>A identidade da transação, para fins de idempotência, é a descrição mais o multiconjunto
   * de {@code (conta, direção, valor)}; ver {@link IdempotencyContent}.
   *
   * @return {@link PostResult#POSTED} quando gravou, {@link PostResult#DUPLICATE} quando
   *         já existia transação com a mesma {@code idempotencyKey} e o mesmo conteúdo, nada
   *         tendo sido alterado.
   * @throws IdempotencyConflictException quando a chave de idempotência já existe com
   *         conteúdo diferente do recebido.
   *         Se um lançamento falhar, nada é gravado e a exceção de infraestrutura sobe.
   */
  PostResult save(Transaction transaction);

  Optional<Transaction> findById(UUID id);

  Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

  /**
   * Saldo derivado dos lançamentos da conta, em centavos inteiros, respeitando o lado
   * normal do tipo de conta: {@code ASSET}/{@code EXPENSE} somam débito,
   * {@code LIABILITY}/{@code EQUITY}/{@code REVENUE} somam crédito.
   *
   * <p>Pode ser negativo: crédito maior que débito numa conta de ativo é um saldo
   * devedor/credor invertido, e o ledger não esconde isso.
   *
   * @throws IllegalArgumentException se a conta não existir. Conta existente sem
   *         lançamentos devolve {@code 0}, que é diferente de conta inexistente.
   */
  long balanceOf(UUID accountId);
}
