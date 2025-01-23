package com.axonivy.github.file;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Objects;

import com.axonivy.github.Logger;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CharSequenceReader;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import com.axonivy.github.DryRun;
import com.axonivy.github.GitHubProvider;
import com.axonivy.github.file.GitHubFiles.FileMeta;
import static com.axonivy.github.constant.Constants.GIT_HEAD;

public class GitHubFilesRemover {

  private static final String GITHUB_ORG = ".github";
  private static final Logger LOG = new Logger();
  private boolean isNotSync;
  private final FileReference reference;
  private final GitHub github;
  private GHUser ghActor;

  public GitHubFilesRemover(FileMeta fileMeta, String user) throws IOException {
    Objects.requireNonNull(fileMeta);
    this.reference = new FileReference(fileMeta);
    this.github = GitHubProvider.get();
    this.ghActor = github.getUser(user);
  }

  public int removeFile(List<String> orgNames) throws IOException {
    Objects.requireNonNull(orgNames);
    LOG.info("Working on organizations: {0}.", orgNames);
    for (var orgName : orgNames) {
      var org = github.getOrganization(orgName);
      for (var repo : List.copyOf(org.getRepositories().values())) {
        removeRepoFile(repo);
      }
    }
    if (isNotSync) {
      LOG.error("At least one repository has {0}.", reference.meta().filePath());
      LOG.error("Remove {0} manually or run the build without DRYRUN to remove {0} from the repository.",
          reference.meta().filePath());
      return -1;
    }
    return 0;
  }

  private void removeRepoFile(GHRepository repo) throws IOException {
    if (GITHUB_ORG.equals(repo.getName())) {
      return;
    }
    if (repo.isPrivate() || repo.isArchived()) {
      LOG.info("Repo {0} is {1}.", repo.getFullName(), repo.isPrivate() ? "private" : "archived");
      return;
    }

    var foundFile = getFileContent(reference.meta().filePath(), repo);
    if (foundFile != null) {
      if (hasSimilarContent(foundFile)) {
        LOG.info("Repo {0} contains {1}", repo.getFullName(), foundFile.getName());
        isNotSync = true;
        handleRemoveFile(repo, foundFile);
      } else {
        LOG.info("Repo {0} has {1}, but the content is different.", repo.getFullName(), foundFile.getName());
      }
    }
  }

  private GHContent getFileContent(String path, GHRepository repo) {
    try {
      return repo.getFileContent(path);
    } catch (Exception e) {
      LOG.error("File {0} in repo {1} is not found.", path, repo.getFullName());
      return null;
    }
  }

  private boolean hasSimilarContent(GHContent existingFile) throws IOException {
    Reader targetContent = new CharSequenceReader(new String(reference.content()));
    Reader actualContent = new CharSequenceReader(new String(existingFile.read().readAllBytes()));
    return IOUtils.contentEqualsIgnoreEOL(targetContent, actualContent);
  }

  private void handleRemoveFile(GHRepository repo, GHContent foundFile) throws IOException {
    try {
      if (DryRun.is()) {
        isNotSync = true;
        LOG.info("DRYRUN: ");
      } else {
        removeFileOnGit(repo, foundFile);
      }
      LOG.info("Repo {0} file {1} removed.", repo.getFullName(), reference.meta().filePath());
    } catch (IOException ex) {
      LOG.error("Cannot remove {0} from repo {1}.", repo.getFullName(), reference.meta().filePath());
      throw ex;
    }
  }

  private void removeFileOnGit(GHRepository repo, GHContent foundFile) throws IOException {
    var defaultBranch = repo.getBranch(repo.getDefaultBranch());
    var sha1 = defaultBranch.getSHA1();
    repo.createRef(GIT_HEAD + reference.meta().branchName(), sha1);
    foundFile.delete(reference.meta().commitMessage(), reference.meta().branchName());
    var pr = repo.createPullRequest(reference.meta().pullRequestTitle(), reference.meta().branchName(), repo.getDefaultBranch(), "");
    if (ghActor != null) {
      pr.setAssignees(ghActor);
    }
    LOG.info("Review the PR on "+ pr.getHtmlUrl());
    //pr.merge(reference.meta().commitMessage());
  }

}
