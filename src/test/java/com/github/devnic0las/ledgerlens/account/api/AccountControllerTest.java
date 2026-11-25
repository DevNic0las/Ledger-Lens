package com.github.devnic0las.ledgerlens.account.api;

import com.github.devnic0las.ledgerlens.account.application.AccountBalanceService;
import com.github.devnic0las.ledgerlens.account.domain.exception.AccountNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountController.class)
class AccountControllerTest {

  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Autowired
  MockMvc mvc;

  @MockitoBean
  AccountBalanceService service;

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
}
