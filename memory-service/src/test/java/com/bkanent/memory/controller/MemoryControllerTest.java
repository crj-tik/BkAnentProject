package com.bkanent.memory.controller;

import com.bkanent.memory.service.SessionSharedMemoryService;
import com.bkanent.memory.service.TaskArtifactMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemoryControllerTest {

    @Test
    void resolvesSessionAndArtifactQueryParametersWithoutCompilerParameterNames() throws Exception {
        SessionSharedMemoryService sessions = mock(SessionSharedMemoryService.class);
        TaskArtifactMemoryService artifacts = mock(TaskArtifactMemoryService.class);
        when(sessions.find("session-1")).thenReturn(Optional.empty());
        when(artifacts.listByTaskId("task-1", "session-1")).thenReturn(List.of());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new MemoryController(sessions, artifacts, null, null, null, null)).build();

        mvc.perform(get("/internal/memory/sessions").param("sessionId", "session-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/internal/memory/artifacts/by-task")
                        .param("taskId", "task-1").param("sessionId", "session-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
        verify(sessions).find("session-1");
        verify(artifacts).listByTaskId("task-1", "session-1");
    }
}
