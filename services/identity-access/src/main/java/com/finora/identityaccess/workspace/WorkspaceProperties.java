package com.finora.identityaccess.workspace;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("finora.workspaces")
public class WorkspaceProperties {

    /** PERM-6 proposal: invitations expire after 7 days. */
    private Duration invitationTtl = Duration.ofDays(7);
    /** Safety cap on business workspaces a user may own (abuse limit; an engineering default). */
    private int maxOwnedBusinessWorkspaces = 25;
    /** Where the web app lives; used to build the acceptance link inside invitation messages. */
    private String webBaseUrl = "http://localhost:3000";
    /** DEVELOPMENT ONLY: when set, invitation messages are appended to this file instead of being emailed. */
    private String devOutboxFile;

    public Duration getInvitationTtl() {
        return invitationTtl;
    }

    public void setInvitationTtl(Duration invitationTtl) {
        this.invitationTtl = invitationTtl;
    }

    public int getMaxOwnedBusinessWorkspaces() {
        return maxOwnedBusinessWorkspaces;
    }

    public void setMaxOwnedBusinessWorkspaces(int maxOwnedBusinessWorkspaces) {
        this.maxOwnedBusinessWorkspaces = maxOwnedBusinessWorkspaces;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public String getDevOutboxFile() {
        return devOutboxFile;
    }

    public void setDevOutboxFile(String devOutboxFile) {
        this.devOutboxFile = devOutboxFile;
    }
}
