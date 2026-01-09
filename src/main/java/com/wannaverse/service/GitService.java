package com.wannaverse.service;

import com.wannaverse.persistence.GitRepository;
import com.wannaverse.persistence.GitRepository.AuthType;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.*;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.eclipse.jgit.util.FS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;

@Service
public class GitService {
    private final String cloneDir;
    private final EncryptionService encryptionService;

    public GitService(
            @Value("${app.git.clone-dir}") String cloneDir, EncryptionService encryptionService) {
        this.cloneDir = cloneDir;
        this.encryptionService = encryptionService;
    }

    public Path cloneOrPullRepository(GitRepository repository)
            throws GitAPIException, IOException {
        Path repoPath = Path.of(cloneDir, repository.getId());

        if (Files.exists(repoPath.resolve(".git"))) {
            return pullRepository(repository, repoPath);
        } else {
            return cloneRepository(repository, repoPath);
        }
    }

    public Path cloneRepository(GitRepository repository, Path targetPath)
            throws GitAPIException, IOException {
        Files.createDirectories(targetPath);

        CloneCommand cloneCommand =
                Git.cloneRepository()
                        .setURI(repository.getRepositoryUrl())
                        .setDirectory(targetPath.toFile())
                        .setBranch(repository.getBranch())
                        .setCloneAllBranches(false);

        configureAuth(cloneCommand, repository);

        try (Git git = cloneCommand.call()) {
            return targetPath;
        }
    }

    public Path pullRepository(GitRepository repository, Path repoPath)
            throws GitAPIException, IOException {
        try (Git git = Git.open(repoPath.toFile())) {
            var pullCommand = git.pull().setRemoteBranchName(repository.getBranch());

            if (repository.getAuthType() == AuthType.PAT) {
                String token = encryptionService.decrypt(repository.getEncryptedToken());
                pullCommand.setCredentialsProvider(
                        new UsernamePasswordCredentialsProvider("", token));
            } else if (repository.getAuthType() == AuthType.SSH_KEY) {
                pullCommand.setTransportConfigCallback(createSshTransportCallback(repository));
            }

            pullCommand.call();
        }
        return repoPath;
    }

    public String getLatestCommitSha(GitRepository repository) throws GitAPIException {
        Collection<Ref> refs =
                Git.lsRemoteRepository()
                        .setRemote(repository.getRepositoryUrl())
                        .setHeads(true)
                        .setCredentialsProvider(getCredentialsProvider(repository))
                        .setTransportConfigCallback(
                                repository.getAuthType() == AuthType.SSH_KEY
                                        ? createSshTransportCallback(repository)
                                        : null)
                        .call();

        String targetBranch = "refs/heads/" + repository.getBranch();
        for (Ref ref : refs) {
            if (ref.getName().equals(targetBranch)) {
                return ref.getObjectId().getName();
            }
        }
        return null;
    }

    public String getCurrentCommitSha(Path repoPath) throws IOException {
        try (Git git = Git.open(repoPath.toFile())) {
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    public void deleteRepository(String repositoryId) throws IOException {
        Path repoPath = Path.of(cloneDir, repositoryId);
        if (Files.exists(repoPath)) {
            Files.walk(repoPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    private void configureAuth(CloneCommand cloneCommand, GitRepository repository) {
        if (repository.getAuthType() == AuthType.PAT) {
            String token = encryptionService.decrypt(repository.getEncryptedToken());
            cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider("", token));
        } else if (repository.getAuthType() == AuthType.SSH_KEY) {
            cloneCommand.setTransportConfigCallback(createSshTransportCallback(repository));
        }
    }

    private CredentialsProvider getCredentialsProvider(GitRepository repository) {
        if (repository.getAuthType() == AuthType.PAT) {
            String token = encryptionService.decrypt(repository.getEncryptedToken());
            return new UsernamePasswordCredentialsProvider("", token);
        }
        return null;
    }

    private TransportConfigCallback createSshTransportCallback(GitRepository repository) {
        return transport -> {
            if (transport instanceof SshTransport sshTransport) {
                File sshDir = new File(FS.DETECTED.userHome(), ".ssh");
                SshdSessionFactory sshSessionFactory =
                        new SshdSessionFactoryBuilder()
                                .setPreferredAuthentications("publickey")
                                .setHomeDirectory(FS.DETECTED.userHome())
                                .setSshDirectory(sshDir)
                                .build(null);
                sshTransport.setSshSessionFactory(sshSessionFactory);
            }
        };
    }
}
