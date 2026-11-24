package com.github.devnic0las.ledgerlens.ledger.application;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import com.github.devnic0las.ledgerlens.ledger.domain.PostResult;
import com.github.devnic0las.ledgerlens.ledger.domain.Transaction;
import com.github.devnic0las.ledgerlens.ledger.domain.exception.AccountsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Caso de uso de lançamento. Depende apenas das portas do domínio; quem grava de fato é
 * {@link LedgerRepository} e quem sabe se as contas existem é {@link AccountRepository}.
 *
 * <p>{@code @Transactional} mora aqui, não no adaptador: a unidade de trabalho é o caso de
 * uso. Isso exige que {@code save} não aborte a transação em caso de duplicata — motivo pelo
 * qual o adaptador usa {@code ON CONFLICT ... DO NOTHING} em vez de capturar a violação de
 * {@code UNIQUE} (que no Postgres aborta a transação inteira com SQLState {@code 25P02}).
 */
@Service
public class PostTransactionService {

  private final LedgerRepository ledgerRepository;
  private final AccountRepository accountRepository;

  public PostTransactionService(LedgerRepository ledgerRepository, AccountRepository accountRepository) {
    this.ledgerRepository = ledgerRepository;
    this.accountRepository = accountRepository;
  }

  /**
   * Grava a transação de forma atômica.
   *
   * @return {@code POSTED} com a transação gravada, ou {@code DUPLICATE} com a transação
   *         original que já ocupava a chave.
   * @throws AccountsNotFoundException se algum {@code accountId} não existir. As contas são
   *         conferidas antes de qualquer escrita: a FK do banco é rede de segurança, não o
   *         caminho normal de erro.
   * @throws com.github.devnic0las.ledgerlens.ledger.domain.exception.IdempotencyConflictException
   *         se a chave já existir com conteúdo diferente. Não é capturada aqui de propósito:
   *         propagar marca a transação como rollback-only e nada commita.
   */
  @Transactional
  public PostTransactionResult post(PostTransactionCommand command) {
    // Domínio primeiro: uma transação torta (menos de 2 lançamentos ou débitos ≠ créditos)
    // é inválida por si só e nem chega a consultar o banco.
    Transaction transaction = command.toTransaction(UUID.randomUUID(), Instant.now());

    Set<UUID> requested = new LinkedHashSet<>();
    command.entries().forEach(entry -> requested.add(entry.accountId()));

    Map<UUID, Account> found = accountRepository.findAllByIds(requested);
    Set<UUID> missing = new LinkedHashSet<>(requested);
    missing.removeAll(found.keySet());
    if (!missing.isEmpty()) {
      throw new AccountsNotFoundException(missing);
    }

    return switch (ledgerRepository.save(transaction)) {
      case POSTED -> new PostTransactionResult(transaction, PostResult.POSTED);
      case DUPLICATE -> new PostTransactionResult(
              ledgerRepository.findByIdempotencyKey(command.idempotencyKey())
                      .orElseThrow(() -> new IllegalStateException(
                              "Chave já existente sem transação visível: " + command.idempotencyKey())),
              PostResult.DUPLICATE);
    };
  }
}
