# Android Files and Git status

The app exposes cloned working trees through the Android Storage Access Framework using the `RepositoryDocumentsProvider`. The provider is limited to valid repositories recorded by `LocalRepositoryStore` and validated by JGit. It exposes document IDs, never filesystem paths.

The provider supports reading, writing, creating, renaming, and deleting working-tree files and directories. Repository roots cannot be renamed or deleted through the provider. `.git`, app metadata, clone markers, traversal paths, absolute paths, and symlink paths are rejected. It supports both legacy internal clones and registered user-selected shared-storage clones. See [clone storage](clone-storage.md) for permission requirements and direct filesystem access.

The provider uses the same working tree that JGit opens. `RepositoryViewModel` refreshes status manually and on lifecycle resume after returning from Android Files. Status comes from `git status`, is sorted by path, and preserves index and working-tree states so a staged file modified again is represented accurately.

For selected-folder clones, **Open in Files** targets the real saved folder using the system external-storage provider. For internal clones it targets the repository in the app provider. Folder resolution/validation runs on IO; activity launch runs on Main. Both paths preserve the exact repository destination in the fallback's `EXTRA_INITIAL_URI`. The app provider implements `findDocumentPath` and uses repository-relative nested document IDs so Files can reconstruct the directory hierarchy.

## Manual verification

1. Open a cloned workspace and choose **Open in Files**.
2. Create a text file, return to the app, and choose **Refresh status**. It must appear as untracked.
3. Edit a tracked file in Files and refresh. It must appear as modified.
4. Delete a tracked file in Files and refresh. It must appear as deleted/missing.
5. When using the app's provider, confirm `.git` is not listed and attempts to address `.git`, `../`, an absolute path, or a symlink escape fail. Browsing shared storage directly follows that file manager's rules; do not modify `.git` there.
6. Confirm the Files UI receives `content://` document URIs, never a raw local path.
7. Restart the app and confirm a valid cloned repository remains available.

The current workspace also provides local staging/commits and remote Git operations. All operations open the same registered working-tree directory.
