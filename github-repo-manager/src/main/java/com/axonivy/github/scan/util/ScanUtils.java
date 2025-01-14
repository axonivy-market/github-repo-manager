package com.axonivy.github.scan.util;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

public class ScanUtils {
  public static String getProceedRepo() {
    return System.getProperty("GITHUB.MARKET.REPO");
  }

  public static List<String> getIgnoreRepos() {
    var ignoreRepos = System.getProperty("GITHUB.MARKET.IGNORE.REPOS");
    return StringUtils.isBlank(ignoreRepos) ? List.of() : Arrays.asList(ignoreRepos.split(","));
  }
}
