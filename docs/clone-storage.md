# Clone into a selected local folder

The repository list displays local clones. Use **+ → choose a GitHub repository → Clone → choose a parent folder → Use this folder**. The app creates `<parent>/<repository name>` and uses that directory directly as the JGit working tree. For example, choosing `Documents/GitRepositories` for `TestingRepo` creates `Documents/GitRepositories/TestingRepo`.

The first attempt requests Android's file access permission when needed. On Android 11 and newer this is **Allow access to manage all files** in system Settings. The SAF picker supplies the user's selection; the external-storage provider's document ID is resolved against a known local storage volume after permission is checked. A generic content URI, cloud provider, or virtual document is never guessed into a path. This use of `MANAGE_EXTERNAL_STORAGE` needs a policy review before Google Play publication; it does not imply store approval.

New selected-folder clones support local device storage and mounted SD card volumes. Android 7–9 uses the legacy storage permission. Android 10 does not grant the required direct shared-directory access at this target SDK and shows a controlled unsupported-device message for new selected-folder clones. Existing internal clones remain available.

Cancelling either the permission dialog or folder picker starts no clone. The repository name must be one safe directory name. Existing destinations are rejected without overwriting or adopting their contents. The destination is registered before JGit starts, and completed clone metadata is saved in the application's private storage. Existing internal clone registrations are preserved. `.git/config` contains a clean HTTPS origin; OAuth credentials are supplied at operation time.

Destination reservations use the existing operation coordinator, so different repositories with the same name cannot concurrently claim the same selected folder. A restored folder-picker result waits for the initial repository catalog load instead of being silently discarded.

Adding, editing, or deleting a file directly in this folder changes the same work tree used by status, stage, commit, fetch, pull, and push. The workspace rereads status on return to the app and supports manual refresh. No secondary copy or synchronization mirror exists. The folder survives application uninstall; private registration metadata and secure authentication do not.

**Open in Files** opens a shared-storage clone at its actual saved folder through the system external-storage document URI, not the Downloads root or the selected parent folder. It validates the persisted location first. Legacy internal clones remain available through the app's DocumentsProvider, which provides document ancestry so Files can navigate directly to that repository. If the device has no directory viewer, the folder-picker fallback receives the same repository URI as its initial location. Missing, moved, or invalid repositories produce a controlled error rather than launching a generic Files screen.

The app's DocumentsProvider hides `.git` and rejects traversal and symlinks. When browsing shared storage directly, other file managers have their own permissions and may show `.git`; the app's provider cannot prevent that. Users should edit working-tree files, leaving `.git` intact. External applications receive only `content://` document URIs, never raw filesystem paths.

**Workspace → gear → Remove clone** permanently deletes the registered local folder, including uncommitted files and unpushed commits. A confirmation explains this. The GitHub repository is unchanged. Removal is serialized with existing Git mutations, verifies the registered path, origin and ownership marker, and does not follow symlinks. Unrelated siblings and the chosen parent folder remain untouched.

## Verification

Unit tests cover folder naming, occupied destinations, unsafe names, non-overwriting behavior, the selected destination reaching the cloner exactly once, error propagation, and local-only list filters. Device tests use isolated registry metadata and local JGit fixtures, with no GitHub dependency or real tokens. They verify physical cloning, direct external edits, provider edits, untracked/modified/deleted status, stage and commit, persisted location after store recreation, hidden `.git`, controlled unsupported providers, and removal without deleting siblings.

Files navigation tests cover persisted destination lookup, invalid saved-parent rejection, exact directory-view/fallback intents, cancellation, nested provider ancestry, and actual system Files displaying fixture contents for shared and legacy internal clones. The legacy UI test temporarily registers its own randomly identified fixture in the app registry and removes only that fixture afterward.

For a real account: allow file access, choose a writable parent under Documents, clone a public/private repository, add a file using Files, return to the workspace, stage and commit it. Restart the app and open the clone again. These live-account steps must be distinguished from deterministic local-fixture tests.
