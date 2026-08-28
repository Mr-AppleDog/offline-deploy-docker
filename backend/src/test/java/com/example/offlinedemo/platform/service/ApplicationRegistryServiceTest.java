package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.config.PlatformProperties;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ApplicationRegistryServiceTest {
    private final PlatformStore store = mock(PlatformStore.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final PlatformProperties properties = new PlatformProperties();
    private final HttpClient http = mock(HttpClient.class);
    private final ApplicationRegistryService service = new ApplicationRegistryService(
            store, crypto, new ObjectMapper(), properties, http);

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

    @Test
    void autoBindsBackendAndUiAndRemovesDuplicateBindings() throws Exception {
        stubRegistry(Map.of("/v2/_catalog?n=1000", """
                {"repositories":["other","docloom-backend","docloom-ui"]}
                """));
        properties.setApplicationRegistryUrl("http://registry.test:5000");
        properties.setApplicationRegistryPullAuthority("localhost:5000");
        Models.Project project = new Models.Project();
        project.id = "project-1";
        project.appKey = "docloom";
        Models.ImageRegistryConfig duplicateOne = registry("old-1", "BACKEND", "http://old:1500", "docloom-backend");
        Models.ImageRegistryConfig duplicateTwo = registry("old-2", "BACKEND", "http://old:1500", "docloom-backend");
        project.imageRegistries.addAll(List.of(duplicateOne, duplicateTwo));
        when(store.project("project-1")).thenReturn(project);

        List<Models.ImageRegistryConfig> result = service.autoBind("project-1");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(value -> value.role).containsExactly("BACKEND", "FRONTEND");
        assertThat(result).extracting(value -> value.repository)
                .containsExactly("docloom-backend", "docloom-ui");
        assertThat(result).allSatisfy(value -> {
            assertThat(value.registryUrl).isEqualTo("http://registry.test:5000");
            assertThat(value.pullAuthority).isEqualTo("localhost:5000");
            assertThat(value.managed).isTrue();
        });
        verify(store).putProject(project);
    }

    @Test
    void readsOciImageTimeArchitectureAndGitRevision() throws Exception {
        String commit = "1234567890abcdef1234567890abcdef12345678";
        stubRegistry(Map.of(
                "/v2/docloom-backend/tags/list?n=200", "{\"tags\":[\"sha-abcdef1\"]}",
                "/v2/docloom-backend/manifests/sha-abcdef1", """
                        {"manifests":[
                          {"digest":"sha256:child","size":321,
                           "platform":{"os":"linux","architecture":"amd64"}}
                        ]}
                        """,
                "/v2/docloom-backend/manifests/sha256:child", """
                        {"config":{"digest":"sha256:config","size":100},
                         "layers":[{"digest":"sha256:layer","size":900}]}
                        """,
                "/v2/docloom-backend/blobs/sha256:config", """
                        {"created":"2026-08-28T23:05:30.145062961+08:00",
                         "os":"linux","architecture":"amd64",
                         "config":{"Labels":{"org.opencontainers.image.revision":"%s",
                                                "org.opencontainers.image.version":"1.2.3"}}}
                        """.formatted(commit)));
        Models.Project project = new Models.Project();
        project.id = "project-1";
        project.targetArch = "amd64";
        Models.ImageRegistryConfig registry = registry("registry-1", "BACKEND",
                "http://registry.test:5000", "docloom-backend");
        project.imageRegistries.add(registry);
        when(store.project("project-1")).thenReturn(project);

        ApplicationRegistryService.ImageCatalog result = service.images("project-1", "registry-1");

        assertThat(result.unavailableTags()).isEmpty();
        assertThat(result.images()).singleElement().satisfies(image -> {
            assertThat(image.tag()).isEqualTo("sha-abcdef1");
            assertThat(image.createdAt()).isEqualTo(Instant.parse("2026-08-28T15:05:30.145062961Z"));
            assertThat(image.gitCommit()).isEqualTo(commit);
            assertThat(image.version()).isEqualTo("1.2.3");
            assertThat(image.architecture()).isEqualTo("linux/amd64");
            assertThat(image.size()).isEqualTo(1321);
        });
    }

    private Models.ImageRegistryConfig registry(String id, String role, String url, String repository) {
        Models.ImageRegistryConfig value = new Models.ImageRegistryConfig();
        value.id = id;
        value.role = role;
        value.registryUrl = url;
        value.repository = repository;
        value.authType = "NONE";
        return value;
    }

    @SuppressWarnings("unchecked")
    private void stubRegistry(Map<String, String> responses) throws Exception {
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            URI uri = request.uri();
            String key = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            HttpResponse<String> response = mock(HttpResponse.class);
            String body = responses.get(key);
            when(response.statusCode()).thenReturn(body == null ? 404 : 200);
            when(response.body()).thenReturn(body == null ? "not found" : body);
            Map<String, List<String>> headers = key.endsWith("sha-abcdef1")
                    ? Map.of("Docker-Content-Digest", List.of("sha256:index")) : Map.of();
            when(response.headers()).thenReturn(HttpHeaders.of(headers, (ignoredName, ignoredValue) -> true));
            return response;
        });
    }
}
