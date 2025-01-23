package com.axonivy.github.scan.model;

import org.kohsuke.github.GHRepository;

public class GitModel {
  private GHRepository repository;
  private String branch;
  private String message;

  public GitModel() {
  }

  public GHRepository getRepository() {
    return repository;
  }

  public void setRepository(GHRepository repository) {
    this.repository = repository;
  }

  public String getBranch() {
    return branch;
  }

  public void setBranch(String branch) {
    this.branch = branch;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
