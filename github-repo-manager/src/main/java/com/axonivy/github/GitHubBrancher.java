package com.axonivy.github;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHRepository;
import static com.axonivy.github.constant.Constants.GIT_HEAD;

public class GitHubBrancher {

  public static void main(String[] args) {
    var github = GitHubProvider.get();

    var dryRun = DryRun.is();
    var minorVersion = StringUtils.trimToEmpty(args[0]);

    System.out.println("Start GitHub repository branching");
    System.out.println("dryRun: " + dryRun);
    System.out.println("minorVersion: " + minorVersion);

    if (StringUtils.isBlank(minorVersion)) {
      throw new IllegalArgumentException("minorVersion is blank");
    }
    if (StringUtils.countMatches(minorVersion, ".") != 1) {
      throw new IllegalArgumentException("minorVersion " + minorVersion + " should have exactly one dot");
    }

    try {
      var repos = GitHubRepos.REPOS_TO_BRANCH;

      var branch = "release/" + minorVersion;
      var baseTag = "base" + minorVersion;
      for (var repo : repos) {
        var r = github.getRepository("axonivy/" + repo);
        new Brancher(r, dryRun, branch, baseTag, minorVersion).run();
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  public static final class Brancher {

    private final GHRepository repo;
    private final boolean dryRun;
    private final String branch;
    private final String tag;
    private final String message;
    private final String minorVersion;

    public Brancher(GHRepository repo, boolean dryRun, String branch, String tag, String minorVersion) {
      this.repo = repo;
      this.dryRun = dryRun;
      this.branch = branch;
      this.tag = tag;
      this.message = tag;
      this.minorVersion = minorVersion;
    }

    public void run() {
      try {
        if (repo.isArchived()) {
          System.out.println("Skipping archived repo " + repo.getFullName());
          return;
        }
        if (repo.getBranches().containsKey(branch)) {
          System.out.println("Skipping repo " + repo.getFullName() + " because it has already a branch " + branch);
          return;
        }

        var ghBranch = repo.getBranch(repo.getDefaultBranch());
        var sha1 = ghBranch.getSHA1();
        if (dryRun) {
          System.out.print("DRYRUN: ");
        }
        System.out.println("Create branch " + branch + " on " + repo.getFullName() + " ~ " + branch + " ~ " + sha1);
        if (!dryRun) {
          repo.createRef(GIT_HEAD + branch, sha1);
        }

        if (dryRun) {
          System.out.print("DRYRUN: ");
        }
        System.out.println("Create tag " + tag + " on " + repo.getFullName() + " ~ " + branch + " ~ " + sha1);
        if (!dryRun) {
          // we create a release and delete it again
          // but the annotated tag still exists
          // I don't know how I could create a annotated tag elseway
          repo.createRelease(tag)
            .name(message)
            .body("Branching for " + minorVersion)
            .commitish(sha1)
            .create()
            .delete();
        }
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }
}
