package com.axonivy.github.scan.model;

public class AppModel {
  private String pom;
  private String assembly;
  private String deployOptions;

  public AppModel(String pom, String assembly, String deployOptions) {
    this.pom = pom;
    this.assembly = assembly;
    this.deployOptions = deployOptions;
  }

  public String getPom() {
    return pom;
  }

  public void setPom(String pom) {
    this.pom = pom;
  }

  public String getAssembly() {
    return assembly;
  }

  public void setAssembly(String assembly) {
    this.assembly = assembly;
  }

  public String getDeployOptions() {
    return deployOptions;
  }

  public void setDeployOptions(String deployOptions) {
    this.deployOptions = deployOptions;
  }
}
