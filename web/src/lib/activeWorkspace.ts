// The "active workspace" is only a UI preference (which workspace to open). It carries no authority: every request is
// authorised by the server from the workspace id in the URL and the caller's live membership.

const KEY = "finora.activeWorkspace";

export function getActiveWorkspaceId(): string | null {
  try {
    return window.localStorage.getItem(KEY);
  } catch {
    return null;
  }
}

export function setActiveWorkspaceId(id: string): void {
  try {
    window.localStorage.setItem(KEY, id);
  } catch {
    // Storage can be unavailable (private mode); switching still works through the URL.
  }
}
