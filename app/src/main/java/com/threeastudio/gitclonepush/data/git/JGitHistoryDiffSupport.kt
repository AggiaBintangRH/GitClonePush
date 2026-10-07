package com.threeastudio.gitclonepush.data.git

import com.threeastudio.gitclonepush.core.model.*
import com.threeastudio.gitclonepush.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.time.Instant

class JGitHistoryDiffSupport(
    private val loader: JGitRepositoryLoader,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val diagnostics: GitDiagnostics = NoOpGitDiagnostics
) : GitHistoryReader, GitDiffReader {
    override suspend fun loadPage(repository: LocalRepository, skip: Int, limit: Int): GitHistoryPage = withContext(ioDispatcher) {
        val event = if (skip == 0) GitDiagnosticEvent.HISTORY_LOAD_STARTED else GitDiagnosticEvent.HISTORY_LOAD_MORE_STARTED
        diagnostics.event(event, safe(repository))
        try {
            loader.open(repository).use { git ->
                val head = git.repository.resolve(Constants.HEAD) ?: return@use GitHistoryPage(emptyList(), false)
                RevWalk(git.repository).use { walk ->
                    walk.markStart(walk.parseCommit(head))
                    repeat(skip) { if (walk.next() == null) return@use GitHistoryPage(emptyList(), false) }
                    val commits = buildList {
                        repeat(limit + 1) { walk.next()?.let { add(summary(it)) } ?: return@repeat }
                    }
                    val result = GitHistoryPage(commits.take(limit), commits.size > limit)
                    diagnostics.event(if (skip == 0) GitDiagnosticEvent.HISTORY_LOAD_SUCCESS else GitDiagnosticEvent.HISTORY_LOAD_MORE_SUCCESS, safe(repository) + mapOf("count" to result.commits.size.toString()))
                    result
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { diagnostics.event(GitDiagnosticEvent.HISTORY_LOAD_FAILED, safe(repository) + mapOf("category" to safeCategory(error))); throw map(error) }
    }

    override suspend fun loadDetail(repository: LocalRepository, commitId: String): GitCommitDetail = withContext(ioDispatcher) {
        diagnostics.event(GitDiagnosticEvent.COMMIT_DETAIL_LOAD_STARTED, safe(repository))
        try {
            loader.open(repository).use { git ->
                RevWalk(git.repository).use { walk ->
                    val commit = git.repository.resolve(commitId)?.let(walk::parseCommit) ?: throw HistoryDiffException(HistoryDiffErrorCategory.COMMIT_NOT_FOUND)
                    walk.parseBody(commit)
                    commit.parents.forEach(walk::parseBody)
                    val parents = commit.parents.map { it.name }
                    val files = diffEntries(git, commit, commit.parents.firstOrNull()).files.map { it.toChangedFile() }
                    GitCommitDetail(commit.name, commit.name.take(7), commit.fullMessage, commit.authorIdent.name, commit.authorIdent.emailAddress, Instant.ofEpochSecond(commit.commitTime.toLong()), parents, files, parents.firstOrNull()).also {
                        diagnostics.event(GitDiagnosticEvent.COMMIT_DETAIL_LOAD_SUCCESS, safe(repository) + mapOf("filesChanged" to files.size.toString()))
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: HistoryDiffException) { diagnostics.event(GitDiagnosticEvent.COMMIT_DETAIL_LOAD_FAILED, safe(repository) + mapOf("category" to error.category.name)); throw error }
        catch (error: Exception) { diagnostics.event(GitDiagnosticEvent.COMMIT_DETAIL_LOAD_FAILED, safe(repository) + mapOf("category" to safeCategory(error))); throw map(error) }
    }

    override suspend fun read(repository: LocalRepository, request: DiffRequest): DiffResult = withContext(ioDispatcher) {
        diagnostics.event(GitDiagnosticEvent.DIFF_LOAD_STARTED, safe(repository) + mapOf("scope" to request.scope.name))
        try {
            loader.open(repository).use { git ->
                val result = when (request.scope) {
                    DiffScope.COMMIT -> commitDiff(git, request)
                    DiffScope.STAGED -> stagedDiff(git, request.path)
                    DiffScope.UNSTAGED -> unstagedDiff(git, request.path)
                }
                diagnostics.event(GitDiagnosticEvent.DIFF_LOAD_SUCCESS, safe(repository) + mapOf("binary" to result.files.any { it.isBinary }.toString(), "truncated" to result.truncated.toString()))
                result
            }
        } catch (error: CancellationException) { throw error }
        catch (error: HistoryDiffException) { diagnostics.event(GitDiagnosticEvent.DIFF_LOAD_FAILED, safe(repository) + mapOf("category" to error.category.name)); throw error }
        catch (error: Exception) { diagnostics.event(GitDiagnosticEvent.DIFF_LOAD_FAILED, safe(repository) + mapOf("category" to safeCategory(error))); throw map(error) }
    }

    private fun commitDiff(git: Git, request: DiffRequest): DiffResult {
        val id = request.commitId ?: throw HistoryDiffException(HistoryDiffErrorCategory.COMMIT_NOT_FOUND)
        RevWalk(git.repository).use { walk ->
            val commit = git.repository.resolve(id)?.let(walk::parseCommit) ?: throw HistoryDiffException(HistoryDiffErrorCategory.COMMIT_NOT_FOUND)
            walk.parseBody(commit)
            commit.parents.forEach(walk::parseBody)
            val parent = request.parentId?.let { git.repository.resolve(it)?.let(walk::parseCommit) } ?: commit.parents.firstOrNull()
            if (request.parentId != null && parent == null) throw HistoryDiffException(HistoryDiffErrorCategory.PARENT_NOT_FOUND)
            return diffEntries(git, commit, parent)
        }
    }

    private fun stagedDiff(git: Git, path: String?): DiffResult {
        return commandDiff(git, path, cached = true)
    }

    private fun unstagedDiff(git: Git, path: String?): DiffResult {
        val result = commandDiff(git, path, cached = false).files.toMutableList()
        val status = git.status().call()
        val untracked = status.untracked.filter { path == null || it == path }
        untracked.filter { candidate -> result.none { it.newPath == candidate && it.changeType == GitChangeType.ADD } }
            .forEach { result += untrackedDiff(git.repository.workTree, it) }
        status.missing.filter { path == null || it == path }
            .filter { candidate -> result.none { it.oldPath == candidate && it.changeType == GitChangeType.DELETE } }
            .forEach { result += deletedDiff(git, it) }
        return DiffResult(result, result.any { it.truncated })
    }

    private fun deletedDiff(git: Git, path: String): FileDiff {
        val head = git.repository.resolve(Constants.HEAD) ?: return FileDiff(path, null, GitChangeType.DELETE, emptyList(), false, false, 0, 0)
        RevWalk(git.repository).use { walk ->
            val commit = walk.parseCommit(head)
            val tree = TreeWalk.forPath(git.repository, path, commit.tree) ?: return FileDiff(path, null, GitChangeType.DELETE, emptyList(), false, false, 0, 0)
            val bytes = git.repository.open(tree.getObjectId(0)).openStream().use { it.readBounded(MAX_BYTES + 1) }
            val truncated = bytes.size > MAX_BYTES
            val content = bytes.take(MAX_BYTES).toByteArray()
            if (content.any { it.toInt() == 0 }) return FileDiff(path, null, GitChangeType.DELETE, emptyList(), true, truncated, null, null)
            val lines = content.toString(Charsets.UTF_8).split('\n').map { DiffLine(DiffLineType.REMOVED, it) }
            return FileDiff(path, null, GitChangeType.DELETE, listOf(DiffHunk(1, lines.size, 0, 0, lines)), truncated, false, 0, lines.size)
        }
    }

    private fun commandDiff(git: Git, path: String?, cached: Boolean): DiffResult {
        validatePath(path)
        val output = LimitedOutputStream(MAX_BYTES)
        val command = git.diff().setOutputStream(output).setCached(cached)
        path?.let { command.setPathFilter(PathFilter.create(it)) }
        command.call()
        val parsed = parsePatchFiles(output.toByteArray().toString(Charsets.UTF_8), output.truncated)
        return DiffResult(parsed, output.truncated || parsed.any { it.truncated })
    }

    private fun diffEntries(git: Git, commit: RevCommit, parent: RevCommit?): DiffResult {
        val old = parent?.let { treeIterator(git, it.tree.id) } ?: EmptyTreeIterator()
        val new = treeIterator(git, commit.tree.id) ?: EmptyTreeIterator()
        return scan(git, old, new, null)
    }

    private fun treeIterator(git: Git, id: ObjectId?): AbstractTreeIterator? = id?.let { CanonicalTreeParser().apply { reset(git.repository.newObjectReader(), it) } }

    private fun scan(git: Git, old: AbstractTreeIterator, new: AbstractTreeIterator, path: String?): DiffResult {
        validatePath(path)
        val output = mutableListOf<FileDiff>()
        val formatter = DiffFormatter(ByteArrayOutputStream())
        formatter.setRepository(git.repository)
        formatter.setDetectRenames(true)
        formatter.setDiffComparator(RawTextComparator.DEFAULT)
        val entries = formatter.scan(old, new).filter { path == null || it.oldPath == path || it.newPath == path }
        formatter.close()
        entries.forEach { entry -> output += formatEntry(git, entry) }
        return DiffResult(output, output.any { it.truncated })
    }

    private fun formatEntry(git: Git, entry: DiffEntry): FileDiff {
        val bytes = LimitedOutputStream(MAX_BYTES)
        DiffFormatter(bytes).apply {
            setRepository(git.repository)
            setDetectRenames(true)
            setDiffComparator(RawTextComparator.DEFAULT)
            try { format(entry) } finally { close() }
        }
        val text = bytes.toByteArray().toString(Charsets.UTF_8)
        val binary = text.contains("Binary files") || text.contains("GIT binary patch")
        val parsed = if (binary) ParsedPatch(emptyList(), false) else parsePatch(text)
        return FileDiff(entry.oldPath.takeUnless { it == DiffEntry.DEV_NULL }, entry.newPath.takeUnless { it == DiffEntry.DEV_NULL }, entry.changeType.toDomain(), parsed.hunks, binary, bytes.truncated || parsed.truncated, parsed.additions, parsed.deletions)
    }

    private fun untrackedDiff(root: File, path: String): FileDiff {
        validatePath(path)
        val file = File(root, path)
        if (!file.isFile) return FileDiff(null, path, GitChangeType.ADD, emptyList(), false, false, 0, 0)
        val bytes = file.inputStream().use { it.readBounded(MAX_BYTES + 1) }
        val truncated = bytes.size > MAX_BYTES
        val content = bytes.take(MAX_BYTES).toByteArray()
        if (content.any { it.toInt() == 0 }) return FileDiff(null, path, GitChangeType.ADD, emptyList(), true, truncated, null, null)
        val lines = content.toString(Charsets.UTF_8).split('\n').map { DiffLine(DiffLineType.ADDED, it) }
        return FileDiff(null, path, GitChangeType.ADD, listOf(DiffHunk(0, 0, 1, lines.size, lines)), truncated, false, lines.size, 0)
    }

    private data class ParsedPatch(val hunks: List<DiffHunk>, val truncated: Boolean) { val additions get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.ADDED } }; val deletions get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.REMOVED } } }
    private fun parsePatch(patch: String): ParsedPatch {
        val hunks = mutableListOf<DiffHunk>(); var current: DiffHunkBuilder? = null; var rendered = 0; var truncated = false
        val regex = Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")
        patch.lineSequence().forEach { line ->
            val match = regex.find(line)
            if (match != null) {
                current?.let { hunks += it.build() }; current = DiffHunkBuilder(match.groupValues[1].toInt(), match.groupValues[2].ifBlank { "1" }.toInt(), match.groupValues[3].toInt(), match.groupValues[4].ifBlank { "1" }.toInt()); return@forEach
            }
            val builder = current ?: return@forEach
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("\\")) return@forEach
            if (rendered >= MAX_LINES) { truncated = true; return@forEach }
            when { line.startsWith("+") -> builder.lines += DiffLine(DiffLineType.ADDED, line.drop(1)); line.startsWith("-") -> builder.lines += DiffLine(DiffLineType.REMOVED, line.drop(1)); line.startsWith(" ") -> builder.lines += DiffLine(DiffLineType.CONTEXT, line.drop(1)) }
            rendered++
        }
        current?.let { hunks += it.build() }
        return ParsedPatch(hunks, truncated)
    }

    private fun parsePatchFiles(patch: String, outputTruncated: Boolean): List<FileDiff> {
        val files = mutableListOf<FileDiffBuilder>()
        var current: FileDiffBuilder? = null
        patch.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ")) {
                current?.let { files += it }
                current = FileDiffBuilder()
            }
            val builder = current ?: return@forEach
            when {
                line.startsWith("--- ") -> builder.oldPath = line.removePrefix("--- ").removePrefix("a/").takeUnless { it == "/dev/null" }
                line.startsWith("+++ ") -> builder.newPath = line.removePrefix("+++ ").removePrefix("b/").takeUnless { it == "/dev/null" }
                line.contains("Binary files") || line.contains("GIT binary patch") -> builder.binary = true
            }
            builder.patchLines += line
        }
        current?.let { files += it }
        return files.map { builder ->
            val old = builder.oldPath; val new = builder.newPath
            val type = when { old == null -> GitChangeType.ADD; new == null -> GitChangeType.DELETE; old != new -> GitChangeType.RENAME; else -> GitChangeType.MODIFY }
            val parsed = if (builder.binary) ParsedPatch(emptyList(), false) else parsePatch(builder.patchLines.joinToString("\n"))
            FileDiff(old, new, type, parsed.hunks, builder.binary, outputTruncated || parsed.truncated, parsed.additions.takeUnless { builder.binary }, parsed.deletions.takeUnless { builder.binary })
        }
    }

    private data class DiffHunkBuilder(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: MutableList<DiffLine> = mutableListOf()) { fun build() = DiffHunk(oldStart, oldCount, newStart, newCount, lines.toList()) }
    private data class FileDiffBuilder(var oldPath: String? = null, var newPath: String? = null, var binary: Boolean = false, val patchLines: MutableList<String> = mutableListOf())
    private fun DiffEntry.ChangeType.toDomain() = when (this) { DiffEntry.ChangeType.ADD -> GitChangeType.ADD; DiffEntry.ChangeType.DELETE -> GitChangeType.DELETE; DiffEntry.ChangeType.RENAME -> GitChangeType.RENAME; DiffEntry.ChangeType.COPY -> GitChangeType.COPY; DiffEntry.ChangeType.MODIFY -> GitChangeType.MODIFY }
    private fun FileDiff.toChangedFile() = GitChangedFile(newPath ?: oldPath.orEmpty(), oldPath, changeType, additions, deletions, isBinary)
    private fun DiffResult.toMutableFiles() = files.toMutableList()
    private fun validatePath(path: String?) { if (path != null && (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.split('/').any { it == "." || it == ".." || it == ".git" || it.isBlank() })) throw HistoryDiffException(HistoryDiffErrorCategory.PATH_NOT_FOUND) }
    private fun summary(commit: RevCommit) = GitCommitSummary(commit.name, commit.name.take(7), commit.shortMessage, commit.authorIdent.name, commit.authorIdent.emailAddress, Instant.ofEpochSecond(commit.commitTime.toLong()), commit.parentCount)
    private fun safe(repository: LocalRepository) = mapOf("repositoryId" to repository.id)
    private fun safeCategory(error: Throwable) = (error as? HistoryDiffException)?.category?.name ?: error::class.simpleName.orEmpty()
    private fun map(error: Exception) = when (error) { is HistoryDiffException -> error; else -> HistoryDiffException(HistoryDiffErrorCategory.READ_FAILED, error) }
    private companion object { const val MAX_BYTES = 256 * 1024; const val MAX_LINES = 4000 }
}

private class LimitedOutputStream(private val limit: Int) : OutputStream() {
    private val delegate = ByteArrayOutputStream(limit)
    var truncated: Boolean = false
        private set
    override fun write(value: Int) { if (delegate.size() < limit) delegate.write(value) else truncated = true }
    override fun write(bytes: ByteArray, offset: Int, length: Int) { val remaining = limit - delegate.size(); if (remaining > 0) delegate.write(bytes, offset, minOf(remaining, length)); if (length > remaining) truncated = true }
    fun toByteArray() = delegate.toByteArray()
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(minOf(limit, 8192))
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        if (count == 0) {
            val next = read()
            if (next < 0) break
            output.write(next)
        } else output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
