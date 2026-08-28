package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationRegistryServiceTest {
    private final PlatformStore store = mock(PlatformStore.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final ApplicationRegistryService service = new ApplicationRegistryService(store, crypto, new ObjectMapper());

    @Test
    void encryptsCredentialAndNeverReturnsCipherInView() {
        Models.Project project = new Models.Project();
        project.id = "project-1";
        when(store.project("project-1")).thenReturn(project);
        when(crypto.encrypt("harbor-token")).thenReturn("encrypted-value");
        ApplicationRegistryService.RegistryInput input = new ApplicationRegistryService.RegistryInput();
        input.role = "backend";
        input.registryUrl = "harbor.example.com/";
        input.repository = "Team/Order-Backend";
        input.authType = "basic";
        input.username = "robot$builder";
        input.secret = "harbor-token";

        Models.ImageRegistryConfig saved = service.save("project-1", null, input);
        Map<String, Object> view = service.view(saved);

        assertThat(saved.registryUrl).isEqualTo("https://harbor.example.com");
        assertThat(saved.repository).isEqualTo("team/order-backend");
        assertThat(saved.secretCipher).isEqualTo("encrypted-value");
        assertThat(view).containsEntry("credentialConfigured", true);
        assertThat(view).doesNotContainKeys("secret", "secretCipher");
        verify(store).putProject(project);
    }
}
