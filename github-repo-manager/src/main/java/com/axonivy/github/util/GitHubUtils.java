package com.axonivy.github.util;

import com.axonivy.github.DryRun;
import com.axonivy.github.Logger;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.HttpException;

import java.io.IOException;

import static com.axonivy.github.constant.Constants.GIT_HEAD;

public class GitHubUtils {
  private static final Logger LOG = new Logger();

  public static void createBranchIfMissing(GHRepository repository, String branchName) {
    try {
      repository.createRef(GIT_HEAD.concat(branchName),
          repository.getBranch(repository.getDefaultBranch()).getSHA1());
    } catch (IOException e) {
      LOG.info("Branch already exists or could not be created: {0}", e.getMessage());
    }
  }

  public static void commitNewFile(GHRepository repository, String branch, String path, String message, String content)
      throws Exception {
    if (DryRun.is()) {
      LOG.info("DRY RUN: ");
      LOG.info("File created: {0}", path);
      return;
    }
    try {
      repository.getFileContent(path, branch)
          .update(content, message, branch);
      LOG.info("File already exists, did a update: {0}", path);
    } catch (Exception e) {
      repository.createContent()
          .path(path)
          .branch(branch)
          .message(message)
          .content(content)
          .commit();
      LOG.info("File created: {0}", path);
    }
  }

  public static void createPullRequest(GHUser ghActor, GHRepository repository, String branch, String title, String message)
      throws IOException {
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
    }
  }

  public static String extractActor(String[] args) {
    String user = "";
    if (args.length > 0) {
      user = args[0];
      LOG.info("Running updates triggered by user " + user);
    }
    return user;
  }
}
