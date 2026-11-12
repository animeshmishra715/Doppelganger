package dev.doppelganger.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doppelganger.core.DomainSpec;
import dev.doppelganger.core.RecordKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The stock example end to end over HTTP and the database. */
@SpringBootTest
@AutoConfigureMockMvc
class SyncApiTest {

    @Autowired MockMvc mvc;
    @Autowired SyncService service;
    @Autowired OperationRepository repository;
    @Autowired ObjectMapper json;
    @Autowired DomainSpec spec;

    private static String op(String opId, String device, String kind, String field, String value, long base, String hlc) {
        return """
                {"opId":"%s","deviceId":"%s","recordType":"item","recordId":"item-1","field":%s,
                 "kind":"%s","value":%s,"baseVersion":%d,"hlc":"%s"}"""
                .formatted(opId, device, field == null ? "null" : "\"" + field + "\"", kind, value, base, hlc);
    }

    private void push(String... ops) throws Exception {
        mvc.perform(post("/api/sync/push").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ops\":[" + String.join(",", ops) + "]}"))
                .andExpect(status().isOk());
    }

    @Test
    void stockExampleOverHttp() throws Exception {
        String create = op("a-1", "device-a", "create", null, "null", 0, "2026-11-20T10:00:00.000Z-0000-device-a");
        String stock = op("a-2", "device-a", "set", "quantity", "10", 0, "2026-11-20T10:00:00.000Z-0001-device-a");
        push(create, stock);

        // Both devices saw version 2 (quantity 10), then sold offline.
        String saleA = op("a-3", "device-a", "set", "quantity", "8", 2, "2026-11-20T11:00:00.000Z-0000-device-a");
        String saleB = op("b-1", "device-b", "set", "quantity", "7", 2, "2026-11-20T11:05:00.000Z-0000-device-b");
        push(saleA);
        push(saleB);
        push(saleB);        // resend: must change nothing

        mvc.perform(get("/api/sync/pull").param("since", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.head").value(5))
                .andExpect(jsonPath("$.entries", hasSize(5)))
                .andExpect(jsonPath("$.entries[3].disposition").value("MERGED"))
                .andExpect(jsonPath("$.entries[4].op.deviceId").value("server"))
                .andExpect(jsonPath("$.entries[4].op.kind").value("set"))
                .andExpect(jsonPath("$.entries[4].op.value").value(5));

        mvc.perform(get("/api/sync/pull").param("since", "4"))
                .andExpect(jsonPath("$.entries", hasSize(1)));

        // A server restarted on the same database rebuilds the same state from the operations table.
        SyncService restarted = new SyncService(repository, json, spec);
        assertThat(restarted.snapshot()).isEqualTo(service.snapshot());
        assertThat(restarted.snapshot().get(new RecordKey("item", "item-1")).value("quantity")).isEqualTo(5L);
    }

    @Test
    void malformedPushIsRejectedWith400() throws Exception {
        mvc.perform(post("/api/sync/push").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ops\":[{\"opId\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }
}
