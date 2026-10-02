package org.harbourcu.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HealthControllerTest {
    @Test
    void returnsUpWithoutDatabase() {
        assertThat(new HealthController().health()).containsEntry("status", "UP");
    }

    @Test
    void servesVersionedHealthEndpoint() throws Exception {
        MockMvcBuilders.standaloneSetup(new HealthController()).build()
            .perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }
}
