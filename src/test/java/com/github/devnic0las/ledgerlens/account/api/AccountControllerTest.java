package com.github.devnic0las.ledgerlens.account.api;

import com.github.devnic0las.ledgerlens.account.application.AccountBalanceService;
import com.github.devnic0las.ledgerlens.account.application.CreateAccountService;
import com.github.devnic0las.ledgerlens.account.domain.Account;
import com.github.devnic0las.ledgerlens.account.domain.AccountType;
import com.github.devnic0las.ledgerlens.account.domain.exception.AccountAlreadyExistsException;
import com.github.devnic0las.ledgerlens.account.domain.exception.AccountNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Autowired
  MockMvc mvc;

  @MockitoBean
  AccountBalanceService service;

  @MockitoBean
  CreateAccountService createService;

  @Test
  void returnsBalanceAsCents() throws Exception {
    when(service.balanceOf(ACCOUNT_ID)).thenReturn(10_000L);

    mvc.perform(get("/accounts/{id}/balance", ACCOUNT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
            .andExpect(jsonPath("$.balanceCents").value(10_000));
  }

  /** Conta inexistente na leitura é 404, não 422: o recurso pedido pela URL não existe. */
  @Test
  void unknownAccountBecomes404() throws Exception {
    when(service.balanceOf(ACCOUNT_ID)).thenThrow(new AccountNotFoundException(ACCOUNT_ID));

    mvc.perform(get("/accounts/{id}/balance", ACCOUNT_ID))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.title").value("Account not found"));
  }

  @Test
  void malformedUuidBecomes400() throws Exception {
    mvc.perform(get("/accounts/{id}/balance", "nao-e-uuid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
  }

  /** O cliente não manda id: o servidor gera e devolve no corpo, com Location apontando para ele. */
  @Test
  void createsAccountAndReturns201WithTheGeneratedId() throws Exception {
    when(createService.create(any())).thenReturn(
            new Account(ACCOUNT_ID, "Conta Corrente", "BRL", AccountType.ASSET));

    mvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"Conta Corrente","currency":"BRL","type":"ASSET"}
                            """))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()))
            .andExpect(jsonPath("$.name").value("Conta Corrente"))
            .andExpect(jsonPath("$.currency").value("BRL"))
            .andExpect(jsonPath("$.type").value("ASSET"));
  }

  /** Duplicar `(name, currency)` conflita com estado existente: 409. */
  @Test
  void duplicateNameAndCurrencyBecomes409() throws Exception {
    when(createService.create(any()))
            .thenThrow(new AccountAlreadyExistsException("Account already exists: Caixa, BRL"));

    mvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"Caixa","currency":"BRL","type":"ASSET"}
                            """))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.title").value("Account already exists"));
  }

  @Test
  void missingFieldBecomes400() throws Exception {
    mvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"currency":"BRL","type":"ASSET"}
                            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));

    org.mockito.Mockito.verifyNoInteractions(createService);
  }

  /** Moeda é validada na forma antes de virar `Account`: 400, não 422. */
  @Test
  void unknownCurrencyBecomes400() throws Exception {
    mvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"Caixa","currency":"BITCOIN","type":"ASSET"}
                            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));

    org.mockito.Mockito.verifyNoInteractions(createService);
  }

  @Test
  void unknownAccountTypeBecomes400() throws Exception {
    mvc.perform(post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"Caixa","currency":"BRL","type":"BITCOIN"}
                            """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400));
  }
}
