package org.harbourcu.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiExceptionHandlerTest {
    @RestController
    static class FixtureController {
        record Request(@NotBlank String name) {}

        @PostMapping("/fixture")
        String save(@Valid @RequestBody Request request) {
            return request.name();
        }
    }

    @Test
    void invalidRequestHasStableFieldErrorAndTraceId() throws Exception {
        MockMvcBuilders.standaloneSetup(new FixtureController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build()
            .perform(post("/fixture").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
            .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
