package com.axonivy.github.scan.model;

import org.apache.maven.model.Model;

import java.util.Set;

public class MavenModel {
  private Model pom;
  private Set<Model> pomModules;

  public MavenModel(Model pom, Set<Model> pomModules) {
    this.pom = pom;
    this.pomModules = pomModules;
  }

  public Model getPom() {
    return pom;
  }

  public void setPom(Model pom) {
    this.pom = pom;
  }

  public Set<Model> getPomModules() {
    return pomModules;
  }

  public void setPomModules(Set<Model> pomModules) {
    this.pomModules = pomModules;
  }
}
