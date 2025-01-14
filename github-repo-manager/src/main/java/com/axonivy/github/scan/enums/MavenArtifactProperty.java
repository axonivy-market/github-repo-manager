package com.axonivy.github.scan.enums;

public enum MavenArtifactProperty {
  ROOT("mavenArtifacts", ""), ID("id", ""), KEY("key", ""), NAME("name", ""), GROUP_ID("groupId", ""),
  ARTIFACT_ID("artifactId", ""), TYPE("type", "zip"), SOURCE_URL("sourceUrl", "https://github.com");
  public final String key;
  public final String defaultValue;

  MavenArtifactProperty(String key, String defaultValue) {
    this.key = key;
    this.defaultValue = defaultValue;
  }
}
