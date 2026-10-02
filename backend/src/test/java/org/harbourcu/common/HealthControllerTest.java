package org.harbourcu.common;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HealthControllerTest {
    @Test
    void returnsUpWithoutDatabase() {
        assertThat(new HealthController().health()).containsEntry("status", "UP");
    }
}

