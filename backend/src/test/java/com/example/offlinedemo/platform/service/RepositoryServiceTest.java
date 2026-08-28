package com.example.offlinedemo.platform.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryServiceTest {
    @Test
    void selectsConfiguredBranchCommit() {
        String output = """
                aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\trefs/heads/develop
                bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\trefs/heads/main
                """;

        assertThat(RepositoryService.selectCommit(output, "main"))
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(RepositoryService.selectCommit(output, "origin/develop"))
                .isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertThat(RepositoryService.selectCommit(output, "refs/remotes/origin/main"))
                .isEqualTo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    }

    @Test
    void selectsDereferencedAnnotatedTag() {
        String output = """
                cccccccccccccccccccccccccccccccccccccccc\trefs/tags/v1.2.3
                dddddddddddddddddddddddddddddddddddddddd\trefs/tags/v1.2.3^{}
                """;

        assertThat(RepositoryService.selectCommit(output, "refs/tags/v1.2.3"))
                .isEqualTo("dddddddddddddddddddddddddddddddddddddddd");
    }

    @Test
    void doesNotFallBackToAnUnrelatedReference() {
        String output = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\trefs/heads/develop";

        assertThat(RepositoryService.selectCommit(output, "main")).isNull();
    }
}
