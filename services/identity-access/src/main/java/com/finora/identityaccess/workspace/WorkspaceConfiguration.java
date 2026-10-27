package com.finora.identityaccess.workspace;

import com.finora.identityaccess.auth.AuthService;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkspaceProperties.class)
class WorkspaceConfiguration {

    @Bean
    @ConditionalOnMissingBean(InvitationNotifier.class)
    InvitationNotifier invitationNotifier(WorkspaceProperties props) {
        String file = props.getDevOutboxFile();
        return file == null || file.isBlank() ? new UnconfiguredInvitationNotifier() : new DevFileInvitationNotifier(Path.of(file));
    }

    @Bean
    WorkspaceService workspaceService(WorkspaceRepository repo, AuthService auth, InvitationNotifier notifier,
            WorkspaceProperties props, PlatformTransactionManager manager, Clock clock) {
        return new WorkspaceService(repo, auth, notifier, props, manager, clock);
    }
}
