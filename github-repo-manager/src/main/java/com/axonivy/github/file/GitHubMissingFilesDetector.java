package com.axonivy.github.file;

import com.axonivy.github.DryRun;
import com.axonivy.github.GitHubProvider;
import com.axonivy.github.file.GitHubFiles.FileMeta;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CharSequenceReader;
import org.kohsuke.github.*;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Objects;

public class GitHubMissingFilesDetector {

  private static final String GITHUB_ORG = ".github";
  private static final String BRANCH_PREFIX = "refs/heads/";
  private static final Logger LOG = new Logger();
  private boolean isNotSync;
  private final FileReference reference;
  private final GitHub github;
  private final GHUser ghActor;

  public GitHubMissingFilesDetector(FileMeta fileMeta, String user) throws IOException {
    Objects.requireNonNull(fileMeta);
    this.reference = new FileReference(fileMeta);
    this.github = GitHubProvider.getGithubToken();
    this.ghActor = github.getUser(user);
  }

  public int requireFile(List<String> orgNames) throws IOException {
    Objects.requireNonNull(orgNames);
    LOG.info("Working on organizations: {0}.", orgNames);
    for (var orgName : orgNames) {
      var org = github.getOrganization(orgName);
      for (var repo : List.copyOf(org.getRepositories().values())) {
        missingFile(repo);
      }
    }
    if (isNotSync) {
      LOG.error("At least one repository has no {0}.", reference.meta().filePath());
      LOG.error("Add a {0} manually or run the build without DRYRUN to add {0} to the repository.",
          reference.meta().filePath());
      return 1;
    }
    return 0;
  }

  private void missingFile(GHRepository repo) throws IOException {
    if (GITHUB_ORG.equals(repo.getName())) {
      return;
    }
    if (repo.isFork()) {
      return;
    }
    if (repo.isPrivate() || repo.isArchived()) {
      LOG.info("Repo {0} is {1}.", repo.getFullName(), repo.isPrivate() ? "private" : "archived");
      return;
    }

    var foundFile = getFileContent(reference.meta().filePath(), repo);
    if (foundFile != null) {
      if (hasSimilarContent(foundFile)) {
        LOG.info("Repo {0} has {1}.", repo.getFullName(), foundFile.getName());
      } else {
        handleOtherContent(repo);
      }
    } else {
      handleMissingFile(repo);
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

  protected boolean hasSimilarContent(GHContent existingFile) throws IOException {
    var fileContent = loadReferenceFileContent(existingFile.getGitUrl());
    if (fileContent == null) {
      return true;
    }
    Reader targetContent = new CharSequenceReader(new String(fileContent));
    Reader actualContent;
    try (var inputStream = existingFile.read()) {
      actualContent = new CharSequenceReader(new String(inputStream.readAllBytes()));
    }
    return IOUtils.contentEqualsIgnoreEOL(targetContent, actualContent);
  }

  private void handleMissingFile(GHRepository repo) throws IOException {
    try {
      if (DryRun.is()) {
        isNotSync = true;
        LOG.info("DRYRUN: ");
      } else {
        addMissingFile(repo);
      }
      LOG.info("Repo {0} {1} synced.", repo.getFullName(), reference.meta().filePath());
    } catch (IOException ex) {
      LOG.error("Cannot add {0} to repo {1}.", reference.meta().filePath(), repo.getFullName());
      throw ex;
    }
  }

  private void addMissingFile(GHRepository repo) throws IOException {
    var fileContent = loadReferenceFileContent(repo.getUrl().toString());
    if (fileContent == null) {
      return;
    }
    var defaultBranch = repo.getBranch(repo.getDefaultBranch());
    String refURL = createBranchIfMissing(repo, BRANCH_PREFIX + reference.meta().branchName(), defaultBranch.getSHA1());
    try {
      repo.createContent()
          .branch(refURL)
          .path(reference.meta().filePath())
          .content(fileContent)
          .message(reference.meta().commitMessage())
          .commit();
    } catch (GHFileNotFoundException notFoundException) {
      LOG.error("Commit new file {0} to repo failed due to lack of permissions", reference.meta().filePath());
      isNotSync = true;
    } catch (HttpException e) {
      LOG.error("Commit new file {0} to repo failed due to", e.getMessage());
      isNotSync = true;
    }
    createNewPullRequest(repo, refURL);
  }

  private void createNewPullRequest(GHRepository repo, String refURL) throws IOException {
    try {
      var pr = repo.createPullRequest(reference.meta().pullRequestTitle(), refURL, repo.getDefaultBranch(), "");
      if (ghActor != null) {
        pr.setAssignees(ghActor);
      }
    } catch (HttpException e) {
      LOG.error("Create new pull request failed: {0}", e.getMessage());
      isNotSync = true;
    }
  }

  private String createBranchIfMissing(GHRepository repo, String branchName, String sha) {
    String createdBranch = branchName;
    var isBranchExisted = false;
    try {
      var existedRef = repo.getRef(branchName);
      if (existedRef != null && existedRef.getRef().endsWith(branchName)) {
        createdBranch = existedRef.getRef();
        isBranchExisted = true;
      }
    } catch (Exception exception) {
      LOG.error("Get branch {0} failed", branchName);
    }
    if (!isBranchExisted) {
      try {
        createdBranch = repo.createRef(branchName, sha).getRef();
      } catch (GHFileNotFoundException notFoundException) {
        LOG.error("Create new ref {0} failed due to lack of permissions", branchName);
        isNotSync = true;
      } catch (HttpException e) {
        LOG.error("Create new ref {0} failed: {1}", branchName, e.getMessage());
        isNotSync = true;
      } catch (Exception e) {
        LOG.error("Create new ref {0} failed due to an unexpected error occurred: {1}", branchName, e.getMessage());
        isNotSync = true;
      }
    }
    return createdBranch;
  }

  private void handleOtherContent(GHRepository repo) throws IOException {
    try {
      if (DryRun.is()) {
        isNotSync = true;
        LOG.info("DRYRUN: ");
        LOG.info("Repo {0} has {1} but the content is different from required file {2}.",
            repo.getFullName(), reference.meta().filePath(), reference.meta().filePath());
      } else {
        updateFile(repo);
        LOG.info("Repo {0} {1} synced.", repo.getFullName(), reference.meta().filePath());
      }
    } catch (IOException ex) {
      LOG.error("Cannot update {1} in repo {0}.", repo.getFullName(), reference.meta().filePath());
      throw ex;
    }
  }

  private void updateFile(GHRepository repo) throws IOException {
    var fileContent = loadReferenceFileContent(repo.getUrl().toString());
    if (fileContent == null) {
      return;
    }
    var headBranch = repo.getBranch(repo.getDefaultBranch());
    String refURL = createBranchIfMissing(repo, BRANCH_PREFIX + reference.meta().branchName(), headBranch.getSHA1());

    var existingFile = repo.getFileContent(reference.meta().filePath(), refURL);

    byte[] mergedContent;
    try (var inputStream = existingFile.read()) {
      var existingContent = new String(inputStream.readAllBytes());
      var newContent = new String(fileContent);

      if (existingContent.contains(newContent.trim())) {
        return;
      }

      String merged = existingContent.trim();
      if (!merged.isEmpty() && !merged.endsWith("\n")) {
        merged += "\n";
      }
      merged += newContent;
      mergedContent = merged.getBytes();
    }

    existingFile.update(mergedContent, reference.meta().commitMessage(), refURL);
    createNewPullRequest(repo, refURL);
  }

  protected byte[] loadReferenceFileContent(String repoURL) throws IOException {
    return reference.content();
  }
}
