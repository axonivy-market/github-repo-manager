package com.axonivy.github.util;

import com.axonivy.github.DryRun;
import com.axonivy.github.Logger;
import com.axonivy.github.scan.model.CommitModel;
import com.axonivy.github.scan.model.PullRequestModel;
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

  public static int commitFileChanges(CommitModel commitModel) throws Exception {
    if (commitModel == null || commitModel.getRepository() == null) {
      LOG.info("Invalid param for creating new commit");
      return 1;
    }
    int status = 0;
    if (DryRun.is()) {
      LOG.info("DRY RUN:: File created: {0}", commitModel.getPath());
      return status;
    }
    try {
      GHContent requestFile = commitModel.getRepository().getFileContent(commitModel.getPath(), commitModel.getBranch());
      if (requestFile != null) {
        if (commitModel.isForce()) {
          requestFile.update(commitModel.getContent(), commitModel.getMessage(), commitModel.getBranch());
          LOG.info("File already exists, forced update: {0}/{1}", commitModel.getBranch(), commitModel.getPath());
        } else {
          LOG.error("File already exists, skip update: {0}/{1}", commitModel.getBranch(), commitModel.getPath());
          status = 1;
        }
      }
    } catch (Exception e) {
      commitModel.getRepository().createContent()
          .path(commitModel.getPath())
          .branch(commitModel.getBranch())
          .message(commitModel.getMessage())
          .content(commitModel.getContent())
          .commit();
      LOG.info("File created: {0}", commitModel.getPath());
    }
    return status;
  }

  public static void createPullRequest(PullRequestModel pullRequestModel) throws IOException {
    if (pullRequestModel == null || pullRequestModel.getRepository() == null) {
      LOG.info("Invalid param for creating new Pull Request");
      return;
    }
    if (DryRun.is()) {
      LOG.info("DRY RUN:: Pull request created: {0}", pullRequestModel.getTitle());
      return;
    }
    try {
      GHPullRequest pullRequest = pullRequestModel.getRepository().createPullRequest(pullRequestModel.getTitle(),
          pullRequestModel.getBranch(),
          pullRequestModel.getRepository().getDefaultBranch(),
          pullRequestModel.getMessage());
      if (pullRequestModel.getGhActor() != null) {
        pullRequest.setAssignees(pullRequestModel.getGhActor());
      }
      LOG.info("Pull request created: {0}", pullRequest.getHtmlUrl());
    } catch (HttpException e) {
      LOG.error("An error occurred {0}", e.getMessage());
    }
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
