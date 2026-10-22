package io.github.tangbailin.novamarket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {
    @Autowired MockMvc http;
    @Test void emptyRequestKeyIsBadRequest() throws Exception {
        http.perform(post("/api/activities/unknown/claim").contentType("application/json").content("{\"userId\":\"u\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
    @Test void malformedJsonIsBadRequest() throws Exception {
        http.perform(post("/api/activities").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
    @Test void oversizedKeyIsBadRequest() throws Exception {
        http.perform(post("/api/activities/unknown/claim").header("X-Request-Id","a".repeat(65)).contentType("application/json").content("{\"userId\":\"u\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void unknownActivityIs404() throws Exception {
        http.perform(post("/api/activities/unknown/claim").header("X-Request-Id","valid-id").contentType("application/json").content("{\"userId\":\"u\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACTIVITY_NOT_FOUND"));
    }
    @Test void crossSiteCannotCreateActivities() throws Exception {
        http.perform(post("/api/activities").header("Origin","https://untrusted.example").contentType("application/json").content("{\"name\":\"X\",\"totalStock\":10}"))
                .andExpect(status().isForbidden());
    }
    @Test void invalidLimitDoesNotCreateActivity() throws Exception {
        http.perform(post("/api/activities").contentType("application/json").content("{\"name\":\"X\",\"totalStock\":10,\"perUserLimit\":0}"))
                .andExpect(status().isBadRequest());
    }
}
