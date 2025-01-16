package com.axonivy.github.scan.enums;

import com.axonivy.github.constant.Constants;
import org.apache.commons.lang3.StringUtils;

import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.EMPTY;

public enum MavenProperty {
  ROOT("mavenArtifacts", EMPTY), ID("id", EMPTY), KEY("key", EMPTY), NAME("name", EMPTY), GROUP_ID("groupId", EMPTY),
  ARTIFACT_ID("artifactId", EMPTY), TYPE("type", "zip"), SOURCE_URL("sourceUrl", "https://github.com"),
  PROJECT("project", EMPTY), VERSION("version", EMPTY);

  public final String key;
  public final String defaultValue;

  MavenProperty(String key, String defaultValue) {
    this.key = key;
    this.defaultValue = defaultValue;
  }

  public static String combineProperty(MavenProperty... properties) {
    return StringUtils.joinWith(Constants.DOT, Stream.of(properties).map(MavenProperty::getKey));
  }

  public String getKey() {
    return key;
  }
}
