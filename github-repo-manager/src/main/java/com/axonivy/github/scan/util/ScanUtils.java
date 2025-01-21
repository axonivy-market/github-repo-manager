package com.axonivy.github.scan.util;

import com.axonivy.github.constant.Constants;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;

public class ScanUtils {
  public static final String PRO_PREFIX = "GITHUB.MARKET.";
  public static final String REPO = "REPO";
  public static final String IGNORE_REPOS = "IGNORE.REPOS";
  public static final String VERSION_RANGE = "VERSION.RANGE";

  public static String getProceedRepo() {
    return System.getProperty(PRO_PREFIX + REPO);
  }

  public static List<String> getIgnoreRepos() {
    var ignoreRepos = System.getProperty(PRO_PREFIX + IGNORE_REPOS);
    return StringUtils.isBlank(ignoreRepos) ? List.of() : Arrays.asList(ignoreRepos.split(Constants.COMMA));
  }

  public static Integer[] getVersionRange() {
    var versionRange = System.getProperty(PRO_PREFIX + VERSION_RANGE);
    if (StringUtils.isBlank(versionRange)) {
      return new Integer[]{};
    }
    var versions = versionRange.split(Constants.COMMA);
    return new Integer[]{Integer.parseInt(versions[0]), Integer.parseInt(versions[1])};
  }
}
