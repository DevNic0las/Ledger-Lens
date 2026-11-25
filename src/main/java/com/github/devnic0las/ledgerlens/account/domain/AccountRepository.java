package com.github.devnic0las.ledgerlens.account.domain;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface AccountRepository {

  void save(Account account);
  Map<UUID, Account> findAllByIds(Set<UUID> ids);

}
