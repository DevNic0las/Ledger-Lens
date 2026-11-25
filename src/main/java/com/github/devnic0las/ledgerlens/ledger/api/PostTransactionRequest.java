package com.github.devnic0las.ledgerlens.ledger.api;

import com.github.devnic0las.ledgerlens.ledger.application.PostTransactionCommand;
import com.github.devnic0las.ledgerlens.ledger.domain.Direction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Corpo de {@code POST /transactions}. Não carrega identidade nem instante: o servidor gera
 * ambos, então nada vindo de fora pode colidir com uma PK existente.
 *
 * <p>Só forma é validada aqui ({@code @NotBlank}, {@code @NotEmpty}, {@code @NotNull}): o que
 * falta vira 400. Regra contábil — valor positivo, mínimo de dois lançamentos, débitos =
 * créditos — fica no domínio, e a violação dela vira 422. Duplicar {@code amountCents > 0} com
 * {@code @Positive} faria o mesmo erro responder 400 num caminho e 422 no outro.
 *
 * <p>A chave de idempotência vem no header {@code Idempotency-Key}, não aqui — é metadado de
 * transporte, e é o header que os clientes de pagamento já conhecem.
 */
record PostTransactionRequest(@NotBlank String description,
                             @NotEmpty List<@Valid RequestedEntry> entries) {

  PostTransactionCommand toCommand(String idempotencyKey) {
    return new PostTransactionCommand(
            description,
            idempotencyKey,
            entries.stream()
                    .map(entry -> new PostTransactionCommand.RequestedEntry(
                            entry.accountId(), entry.direction(), entry.amountCents()))
                    .toList());
  }

  record RequestedEntry(@NotNull UUID accountId,
                        @NotNull Direction direction,
                        long amountCents) {
  }
}
