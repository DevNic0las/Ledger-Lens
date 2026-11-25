package com.github.devnic0las.ledgerlens.account.api;

import com.github.devnic0las.ledgerlens.account.application.AccountBalanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code GET /accounts/{id}/balance}: saldo derivado dos lançamentos, em centavos.
 *
 * <p>{@code 200} sempre que a conta existe (inclusive saldo zero ou negativo); {@code 404}
 * quando a conta não existe; {@code 400} quando o id da URL não é UUID.
 */
@RestController
@RequestMapping("/accounts")
class AccountController {

  private final AccountBalanceService service;

  AccountController(AccountBalanceService service) {
    this.service = service;
  }

  @GetMapping("/{id}/balance")
  ResponseEntity<BalanceResponse> balance(@PathVariable UUID id) {
    return ResponseEntity.ok(new BalanceResponse(id, service.balanceOf(id)));
  }

  record BalanceResponse(UUID accountId, long balanceCents) {
  }
}
