package com.example.offlinedemo.platform.service;

import com.example.offlinedemo.platform.catalog.MiddlewareCatalog;
import com.example.offlinedemo.platform.domain.Models;
import com.example.offlinedemo.platform.security.CryptoService;
import com.example.offlinedemo.platform.store.PlatformStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileServiceTest {
    private final PlatformStore store = mock(PlatformStore.class);
    private final ProfileService service = new ProfileService(store, mock(CryptoService.class), mock(MiddlewareCatalog.class));

    @Test
    void createsSiteProfileBoundToProjectTarget() {
        Models.Project project = new Models.Project();
        project.id = "project-1";
        project.currentVersion = "1.2.3";
        project.targetOs = "kylin-v10";
        project.targetArch = "arm64";
        when(store.project("project-1")).thenReturn(project);

        ProfileService.ProfileInput input = new ProfileService.ProfileInput();
        input.projectId = "project-1";
        input.name = "北京生产站点";
        input.middleware = List.of();

        Models.DeploymentProfile profile = service.save(null, input);

        assertThat(profile.projectId).isEqualTo("project-1");
        assertThat(profile.deployedVersion).isEqualTo("1.2.3");
        assertThat(profile.targetArch).isEqualTo("arm64");
        verify(store).putProfile(profile);
    }

    @Test
    void rejectsProfileWithoutProject() {
        ProfileService.ProfileInput input = new ProfileService.ProfileInput();
        input.name = "未归属配置";

        assertThatThrownBy(() -> service.save(null, input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须绑定项目");
    }
}
