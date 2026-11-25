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
 * <p>A porta devolve {@link java.util.OptionalLong}, então "conta não existe" é ausência
 * explícita: o serviço não precisa adivinhar a intenção pelo tipo de exceção, e uma falha de
 * infraestrutura nunca é confundida com 404.
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
    return ledgerRepository.balanceOf(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
  }
}
