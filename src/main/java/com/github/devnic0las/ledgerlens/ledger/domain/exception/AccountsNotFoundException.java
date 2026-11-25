package com.github.devnic0las.ledgerlens.ledger.domain.exception;

import java.util.Set;
import java.util.UUID;

/**
 * Um ou mais ids referenciados pela transação não existem. Carrega exatamente os ids que
 * faltam para que a API possa listá-los na resposta, em vez de responder só "conta inválida".
 *
 * <p>Vive no domínio porque "todas as contas de uma transação precisam existir" é regra de
 * negócio — a FK do banco é só a rede de segurança.
 */
public class AccountsNotFoundException extends RuntimeException {

  private final Set<UUID> missingAccountIds;

  public AccountsNotFoundException(Set<UUID> missingAccountIds) {
    super("Accounts not found: " + missingAccountIds);
    this.missingAccountIds = Set.copyOf(missingAccountIds);
  }

  /** Ids que faltam, sem repetição e sem ordem significativa. Nunca vazio. */
  public Set<UUID> missingAccountIds() {
    return missingAccountIds;
  }
}
