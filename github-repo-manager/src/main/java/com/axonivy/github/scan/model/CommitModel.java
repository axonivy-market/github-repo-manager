package com.axonivy.github.scan.model;

public class CommitModel extends GitModel {
  private String path;
  private String content;
  private boolean force;

  public CommitModel() {
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public boolean isForce() {
    return force;
  }

  public void setForce(boolean force) {
    this.force = force;
  }
}
