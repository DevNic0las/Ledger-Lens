package com.github.devnic0las.ledgerlens.account.application;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountRepository;
import com.github.devnic0las.ledgerlens.account.domain.exception.AccountNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Leitura de contas. Separado de {@link AccountBalanceService} porque o que se lê é outro
 * recurso: a conta em si, não o saldo derivado dela.
 */
@Service
public class AccountQueryService {

  private final AccountRepository accountRepository;

  public AccountQueryService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  /**
   * @throws AccountNotFoundException se a conta não existir. A porta devolve
   *         {@link java.util.Optional#empty()}, então ausência é valor explícito e não se
   *         confunde com falha de infraestrutura.
   */
  @Transactional(readOnly = true)
  public Account findById(UUID id) {
    return accountRepository.findById(id)
            .orElseThrow(() -> new AccountNotFoundException(id));
  }

  /** Lista vazia quando não há conta nenhuma — coleção vazia não é "não encontrado". */
  @Transactional(readOnly = true)
  public List<Account> findAll() {
    return accountRepository.findAll();
  }
}
