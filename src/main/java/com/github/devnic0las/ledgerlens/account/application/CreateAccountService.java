package com.github.devnic0las.ledgerlens.account.application;

import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caso de uso de criação de conta.
 *
 * <p>O conflito de {@code (name, currency)} é decidido pelo banco, não por uma consulta prévia:
 * o adaptador usa {@code ON CONFLICT (name, currency) DO NOTHING} e traduz o conflito para
 * {@link com.github.devnic0las.ledgerlens.account.domain.exception.AccountAlreadyExistsException}.
 * Checar antes abriria uma corrida entre dois pedidos simultâneos com o mesmo nome e moeda.
 */
@Service
public class CreateAccountService {

  private final AccountRepository accountRepository;

  public CreateAccountService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  /**
   * @throws com.github.devnic0las.ledgerlens.account.domain.exception.AccountAlreadyExistsException
   *         se já existir conta com o mesmo {@code (name, currency)}.
   */
  @Transactional
  public Account create(CreateAccountCommand command) {
    Account account = command.toAccount();
    accountRepository.save(account);
    return account;
  }
}
