package com.github.devnic0las.ledgerlens.account.application;

import com.github.devnic0las.ledgerlens.account.domain.exception.AccountNotFoundException;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Leitura de saldo. O saldo continua sendo derivado dos lançamentos no banco, nunca lido de
 * coluna.
 *
 * <p>O adaptador sinaliza conta inexistente com {@link IllegalArgumentException}, o mesmo tipo
 * que outras falhas usam. Traduzir para {@link AccountNotFoundException} aqui é o que permite à
 * borda responder 404 sem adivinhar a intenção pelo tipo de exceção.
 */
@Service
public class AccountBalanceService {

  private final LedgerRepository ledgerRepository;

  public AccountBalanceService(LedgerRepository ledgerRepository) {
    this.ledgerRepository = ledgerRepository;
  }

  /**
   * @throws AccountNotFoundException se a conta não existir. Conta existente sem lançamento
   *         devolve {@code 0}.
   */
  @Transactional(readOnly = true)
  public long balanceOf(UUID accountId) {
    try {
      return ledgerRepository.balanceOf(accountId);
    } catch (IllegalArgumentException exception) {
      throw new AccountNotFoundException(accountId);
    }
  }
}
