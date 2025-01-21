package com.axonivy.github.scan.model;

import org.kohsuke.github.GHUser;

public class PullRequestModel extends GitModel {
  private GHUser ghActor;
  private String title;

  public PullRequestModel() {
  }

  public GHUser getGhActor() {
    return ghActor;
  }

  public void setGhActor(GHUser ghActor) {
    this.ghActor = ghActor;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }
}
