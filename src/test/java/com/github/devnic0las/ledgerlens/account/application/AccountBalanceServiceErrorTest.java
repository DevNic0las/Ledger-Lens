package com.github.devnic0las.ledgerlens.account.application;

import com.github.devnic0las.ledgerlens.account.domain.exception.AccountNotFoundException;
import com.github.devnic0las.ledgerlens.ledger.domain.LedgerRepository;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Borda de erro do serviço de saldo, sem Spring e sem banco: o que se mede aqui é a tradução
 * de "não existe" para 404 e, principalmente, que uma falha de infraestrutura <b>não</b> seja
 * confundida com conta inexistente.
 */
class AccountBalanceServiceErrorTest {

  private final LedgerRepository ledgerRepository = mock(LedgerRepository.class);
  private final AccountBalanceService service = new AccountBalanceService(ledgerRepository);

  private final UUID accountId = UUID.randomUUID();

  @Test
  void emptyBalanceBecomesAccountNotFound() {
    when(ledgerRepository.balanceOf(accountId)).thenReturn(OptionalLong.empty());

    var exception = assertThrows(AccountNotFoundException.class, () -> service.balanceOf(accountId));

    assertEquals(accountId, exception.accountId());
  }

  /**
   * A razão de o adaptador devolver {@code OptionalLong} em vez de lançar: antes, qualquer
   * {@code IllegalArgumentException} — inclusive de um bug — virava 404. Agora só a ausência
   * explícita vira 404; erro inesperado sobe e chega como 500.
   */
  @Test
  void unexpectedRepositoryFailurePropagatesAndIsNot404() {
    var failure = new IllegalStateException("conexão caiu no meio da query");
    when(ledgerRepository.balanceOf(accountId)).thenThrow(failure);

    Throwable thrown = assertThrows(IllegalStateException.class, () -> service.balanceOf(accountId));

    assertEquals(failure, thrown, "a exceção original precisa subir intacta");
    assertFalse(thrown instanceof AccountNotFoundException, "falha de infraestrutura não é 404");
  }

  /** Erro que por acaso é o mesmo tipo que o adaptador usava para "não existe" também propaga. */
  @Test
  void illegalArgumentFromRepositoryIsNotTranslatedInto404() {
    when(ledgerRepository.balanceOf(accountId))
            .thenThrow(new IllegalArgumentException("bug: sql inválido"));

    assertThrows(IllegalArgumentException.class, () -> service.balanceOf(accountId));
  }
}
