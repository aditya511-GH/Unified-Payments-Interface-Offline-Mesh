package com.demo.upimesh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;

    @AfterEach
    void resetMesh() throws Exception {
        mvc.perform(post("/api/mesh/reset")).andExpect(status().isOk());
    }

    @Test
    void readEndpointsReturnActualBackendValuesAndJsonHealthEvenForHtmlAccept() throws Exception {
        mvc.perform(get("/health").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/server-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").isNotEmpty());
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].vpa", hasItem("alice@demo")));
        mvc.perform(get("/api/transactions"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCount").value(4))
                .andExpect(jsonPath("$.availableBalance").value(9000.00))
                .andExpect(jsonPath("$.transactionCount").isNumber());
    }

    @Test
    void meshEndpointsValidatePacketsAndReturnSimulatorResults() throws Exception {
        mvc.perform(post("/api/mesh/inject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packetId\":\"bad/id\",\"ttl\":20,\"createdAt\":0,\"ciphertext\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));

        String packet = json.writeValueAsString(Map.of(
                "packetId", UUID.randomUUID().toString(), "ttl", 5,
                "createdAt", System.currentTimeMillis(), "ciphertext", "not-base64"));
        mvc.perform(post("/api/mesh/inject").contentType(MediaType.APPLICATION_JSON).content(packet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED_FOR_MESH"));
        mvc.perform(post("/api/mesh/gossip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers").isNumber());
        mvc.perform(post("/api/mesh/flush"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadsAttempted").isNumber());
    }

    @Test
    void bridgeIngestReportsInvalidCiphertextWithoutClaimingSettlement() throws Exception {
        String packet = json.writeValueAsString(Map.of(
                "packetId", UUID.randomUUID().toString(), "ttl", 5,
                "createdAt", System.currentTimeMillis(), "ciphertext", "not-base64"));
        mvc.perform(post("/api/bridge/ingest")
                        .header("X-Bridge-Node-Id", "test-bridge")
                        .header("X-Hop-Count", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(packet))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("INVALID"))
                .andExpect(jsonPath("$.reason").value("decryption_failed"));
        mvc.perform(post("/api/bridge/ingest")
                        .header("X-Hop-Count", "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(packet))
                .andExpect(status().isBadRequest());
    }
}
