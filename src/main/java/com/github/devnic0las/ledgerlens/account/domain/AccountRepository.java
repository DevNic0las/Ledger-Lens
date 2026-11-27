package com.github.devnic0las.ledgerlens.account.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Porta de persistência de contas. Vive no domínio para que o domínio não conheça JDBC nem
 * Spring; o adaptador fica em infraestrutura.
 */
public interface AccountRepository {

  /**
   * Grava uma conta nova.
   *
   * @throws com.github.devnic0las.ledgerlens.account.domain.exception.AccountAlreadyExistsException
   *         se já existir conta com o mesmo {@code (name, currency)}.
   */
  void save(Account account);

  Map<UUID, Account> findAllByIds(Set<UUID> ids);

  Optional<Account> findById(UUID id);

  /** Todas as contas, em ordem de inserção no banco. Lista vazia quando não há nenhuma. */
  List<Account> findAll();

}
