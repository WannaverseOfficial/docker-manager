package com.wannaverse.service;

import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.GitRepository.AuthType;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.TransportCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Clones and updates git repositories for deployment.
 *
 * <p>Fixes over the previous version:
 *
 * <ul>
 *   <li>SSH-key authentication used the container user's ~/.ssh instead of the key you entered, so
 *       it never worked. The stored key is now used, and host keys are trusted on first use and
 *       pinned (like {@code StrictHostKeyChecking accept-new}).
 *   <li>Access tokens were sent with an empty username, which some servers reject. The username is
 *       configurable and defaults to "oauth2".
 *   <li>Updates used {@code git pull}, which fails after a force-push or a branch change. They now
 *       fetch and hard-reset to the remote branch (or to an exact commit).
 *   <li>Any URL was accepted, including file:// paths that would read the server's own disk.
 * </ul>
 */
@Service
public class GitService {
    private static final Pattern SCP_LIKE = Pattern.compile("^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:.+$");
    public static final String DEFAULT_TOKEN_USERNAME = "oauth2";

    private final Path cloneDir;
    private final Path sshRoot;
    private final EncryptionService encryptionService;

    public GitService(
            @Value("${app.git.clone-dir}") String cloneDir,
            @Value("${app.data-dir:database}") String dataDir,
            EncryptionService encryptionService) {
        this.cloneDir = Path.of(cloneDir).toAbsolutePath();
        this.sshRoot = Path.of(dataDir).toAbsolutePath().resolve("ssh");
        this.encryptionService = encryptionService;
    }

    /** Throws 400 unless the URL is an http(s), ssh or scp-style (git@host:path) git URL. */
    public static void validateUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            throw bad("Enter the repository URL");
        }
        String u = url.trim();
        boolean ok =
                u.startsWith("https://")
                        || u.startsWith("http://")
                        || u.startsWith("ssh://")
                        || SCP_LIKE.matcher(u).matches();
        if (!ok) {
            throw bad("Use an https://, ssh:// or git@host:path repository URL");
        }
        if (u.contains("\n") || u.contains(" ") || u.startsWith("ssh://-") || u.contains("@-")) {
            throw bad("Invalid repository URL");
        }
    }

    /** Resolves a path inside the repository, rejecting anything that escapes it. */
    public static Path resolveInside(Path repoDir, String relative, String fallback) {
        String rel = relative == null || relative.isBlank() ? fallback : relative.trim();
        Path base = repoDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(rel).normalize();
        if (!resolved.startsWith(base) || rel.startsWith("/")) {
            throw new IllegalArgumentException("Path must stay inside the repository: " + rel);
        }
        return resolved;
    }

    public Path repoPath(GitRepository repository) {
        return cloneDir.resolve(repository.getId());
    }

    /**
     * Makes the local checkout match the remote branch, or {@code commitSha} when given. Returns
     * the checkout directory.
     */
    public Path checkout(GitRepository repository, String commitSha)
            throws GitAPIException, IOException {
        Path repoPath = repoPath(repository);
        try (Auth auth = auth(repository)) {
            if (!Files.isDirectory(repoPath.resolve(".git"))) {
                deleteDir(repoPath);
                Files.createDirectories(repoPath);
                var clone =
                        Git.cloneRepository()
                                .setURI(repository.getRepositoryUrl())
                                .setDirectory(repoPath.toFile())
                                .setBranch(repository.getBranch())
                                .setCloneAllBranches(false);
                auth.apply(clone);
                try (Git git = clone.call()) {
                    // Fails clearly when the branch does not exist (JGit would otherwise leave the
                    // default branch checked out).
                    resetTo(
                            git,
                            commitSha != null
                                    ? commitSha
                                    : "refs/remotes/origin/" + repository.getBranch());
                }
                return repoPath;
            }

            try (Git git = Git.open(repoPath.toFile())) {
                StoredConfig config = git.getRepository().getConfig();
                if (!repository
                        .getRepositoryUrl()
                        .equals(config.getString("remote", "origin", "url"))) {
                    config.setString("remote", "origin", "url", repository.getRepositoryUrl());
                    config.save();
                }
                String branch = repository.getBranch();
                var fetch =
                        git.fetch()
                                .setRemote("origin")
                                .setRefSpecs(
                                        new RefSpec(
                                                "+refs/heads/"
                                                        + branch
                                                        + ":refs/remotes/origin/"
                                                        + branch))
                                .setForceUpdate(true)
                                .setRemoveDeletedRefs(true);
                auth.apply(fetch);
                fetch.call();
                resetTo(git, commitSha != null ? commitSha : "refs/remotes/origin/" + branch);
                git.clean().setCleanDirectories(true).setForce(true).call();
            }
            return repoPath;
        }
    }

    /**
     * Fresh clone into {@code target} for a pipeline run, using the repository's credentials.
     * {@code branch} defaults to the repository's branch; {@code commit}, when given, is checked
     * out exactly. (Pipelines used to clone without credentials and always built the default
     * branch's newest commit, whatever triggered them.)
     */
    public Path checkoutForPipeline(
            GitRepository repository, String branch, String commit, Path target)
            throws GitAPIException, IOException {
        String b = branch == null || branch.isBlank() ? repository.getBranch() : branch;
        Files.createDirectories(target);
        try (Auth auth = auth(repository)) {
            var clone =
                    Git.cloneRepository()
                            .setURI(repository.getRepositoryUrl())
                            .setDirectory(target.toFile())
                            .setBranch(b)
                            .setCloneAllBranches(false);
            auth.apply(clone);
            try (Git git = clone.call()) {
                resetTo(
                        git,
                        commit != null && !commit.isBlank() ? commit : "refs/remotes/origin/" + b);
            }
        }
        return target;
    }

    private static void resetTo(Git git, String ref) throws GitAPIException, IOException {
        ObjectId id = git.getRepository().resolve(ref);
        if (id == null) {
            throw new IllegalArgumentException("Commit or branch not found: " + ref);
        }
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(id.getName()).call();
    }

    /** The newest commit on the configured branch, without cloning. */
    public String getLatestCommitSha(GitRepository repository) throws GitAPIException {
        return branchHeads(repository).get(repository.getBranch());
    }

    /** Every branch on the remote with its newest commit, without cloning. */
    public Map<String, String> branchHeads(GitRepository repository) throws GitAPIException {
        try (Auth auth = auth(repository)) {
            var ls =
                    Git.lsRemoteRepository()
                            .setRemote(repository.getRepositoryUrl())
                            .setHeads(true);
            auth.apply(ls);
            Collection<Ref> refs;
            try {
                refs = ls.call();
            } catch (NullPointerException e) {
                // JGit cannot list a remote that only speaks the old "dumb" HTTP protocol
                // (a plain static file server) without a local repository.
                throw new IllegalStateException(
                        "This git server only supports the old \"dumb\" HTTP protocol, so new"
                                + " commits can't be detected. Deploying still works; use a"
                                + " regular git host (GitHub, GitLab, Gitea) for polling.");
            }
            Map<String, String> heads = new LinkedHashMap<>();
            for (Ref ref : refs) {
                if (ref.getName().startsWith("refs/heads/") && ref.getObjectId() != null) {
                    heads.put(
                            ref.getName().substring("refs/heads/".length()),
                            ref.getObjectId().getName());
                }
            }
            return heads;
        }
    }

    public String getCurrentCommitSha(Path repoPath) throws IOException {
        try (Git git = Git.open(repoPath.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return head == null ? null : head.getName();
        }
    }

    public String getCommitMessage(Path repoPath) {
        try (Git git = Git.open(repoPath.toFile())) {
            var log = git.log().setMaxCount(1).call().iterator();
            return log.hasNext() ? log.next().getShortMessage() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void deleteRepository(String repositoryId) throws IOException {
        deleteDir(cloneDir.resolve(repositoryId));
        deleteDir(sshRoot.resolve(repositoryId));
    }

    // --- authentication ----------------------------------------------------------------------

    /** Credentials for one git operation. Closing it releases the SSH session factory. */
    private final class Auth implements AutoCloseable {
        private CredentialsProvider credentials;
        private SshdSessionFactory ssh;

        void apply(TransportCommand<?, ?> command) {
            if (credentials != null) command.setCredentialsProvider(credentials);
            if (ssh != null) {
                SshdSessionFactory factory = ssh;
                command.setTransportConfigCallback(
                        transport -> {
                            if (transport instanceof SshTransport sshTransport) {
                                sshTransport.setSshSessionFactory(factory);
                            }
                        });
            }
        }

        @Override
        public void close() {
            if (ssh != null) ssh.close();
        }
    }

    private Auth auth(GitRepository repository) {
        Auth auth = new Auth();
        if (repository.getAuthType() == AuthType.PAT && repository.getEncryptedToken() != null) {
            String token = encryptionService.decrypt(repository.getEncryptedToken());
            String user =
                    repository.getTokenUsername() == null || repository.getTokenUsername().isBlank()
                            ? DEFAULT_TOKEN_USERNAME
                            : repository.getTokenUsername();
            auth.credentials = new UsernamePasswordCredentialsProvider(user, token);
        } else if (repository.getAuthType() == AuthType.SSH_KEY) {
            auth.ssh = sshFactory(repository);
        }
        return auth;
    }

    /**
     * An SSH session factory that uses only this repository's key, with its own known_hosts file.
     * Unknown host keys are accepted the first time and then pinned; a changed key is refused.
     */
    private SshdSessionFactory sshFactory(GitRepository repository) {
        if (repository.getEncryptedSshKey() == null) {
            throw new IllegalStateException("No SSH key saved for this repository");
        }
        Path dir = sshRoot.resolve(repository.getId());
        Path key = dir.resolve("id_key");
        try {
            Files.createDirectories(dir);
            String pem = encryptionService.decrypt(repository.getEncryptedSshKey()).strip() + "\n";
            Files.writeString(key, pem, StandardCharsets.UTF_8);
            Files.writeString(
                    dir.resolve("config"),
                    "Host *\n  StrictHostKeyChecking accept-new\n  IdentitiesOnly yes\n",
                    StandardCharsets.UTF_8);
            restrict(key);
            restrict(dir.resolve("config"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare the SSH key: " + e.getMessage(), e);
        }
        File dirFile = dir.toFile();
        return new SshdSessionFactoryBuilder()
                .setPreferredAuthentications("publickey")
                .setHomeDirectory(dirFile)
                .setSshDirectory(dirFile)
                .setDefaultIdentities(d -> List.of(key))
                .build(null);
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not POSIX (Windows development machines).
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
