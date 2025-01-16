package com.axonivy.github.util;

import com.axonivy.github.DryRun;
import com.axonivy.github.Logger;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.*;

import java.io.IOException;

import static com.axonivy.github.constant.Constants.GIT_HEAD;

public class GitHubUtils {
  private static final Logger LOG = new Logger();

  public static int createBranchIfMissing(GHRepository repository, String branchName) {
    int status = 0;
    if (DryRun.is()) {
      LOG.info("DRY RUN:: Branch created: {0}", branchName);
      return status;
    }
    try {
      repository.createRef(GIT_HEAD.concat(branchName),
          repository.getBranch(repository.getDefaultBranch()).getSHA1());
    } catch (IOException e) {
      LOG.error("Branch already exists or could not be created: {0} {1}", branchName, e.getMessage());
      status = 1;
    }
    return status;
  }

  public static int commitFileChanges(GHRepository repository, String branch, String path, String message, String content, boolean force)
      throws Exception {
    int status = 0;
    if (DryRun.is()) {
      LOG.info("DRY RUN:: File created: {0}", path);
      return status;
    }
    try {
      GHContent requestFile = repository.getFileContent(path, branch);
      if (requestFile != null) {
        if (force) {
          requestFile.update(content, message, branch);
          LOG.info("File already exists, forced update: {0}/{1}", branch, path);
        } else {
          LOG.error("File already exists, skip update: {0}/{1}", branch, path);
          status = 1;
        }
      }
    } catch (Exception e) {
      repository.createContent()
          .path(path)
          .branch(branch)
          .message(message)
          .content(content)
          .commit();
      LOG.info("File created: {0}", path);
    }
    return status;
  }

  public static int createPullRequest(GHUser ghActor, GHRepository repository, String branch, String title, String message)
      throws IOException {
    int status = 0;
    if (DryRun.is()) {
      LOG.info("DRY RUN:: Pull request created: {0}", title);
      return status;
    }
    try {
      GHPullRequest pullRequest = repository.createPullRequest(title,
          branch,
          repository.getDefaultBranch(),
          message);
      if (ghActor != null) {
        pullRequest.setAssignees(ghActor);
      }
      LOG.info("Pull request created: {0}", pullRequest.getHtmlUrl());
    } catch (HttpException e) {
      LOG.error("An error occurred {0}", e.getMessage());
      status = 1;
    }
    return status;
  }

  public static String extractActor(String[] args) {
    String user = StringUtils.EMPTY;
    if (args.length > 0) {
      user = args[0];
      LOG.info("Running updates triggered by user " + user);
    }
    return user;
  }
}
