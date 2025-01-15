package com.axonivy.github.scan;

import com.axonivy.github.Logger;
import com.axonivy.github.scan.util.ScanUtils;
import com.axonivy.github.util.GitHubUtils;
import org.apache.commons.lang3.StringUtils;

public class ScanAppProject {
  private static final Logger LOG = new Logger();

  public static void main(String[] args) {
    String repo = ScanUtils.getProceedRepo();
    if (StringUtils.isBlank(repo)) {
      LOG.info("No repo was submitted, job ended!");
      System.exit(0);
    }
    int status = 0;
    try {
      var marketAppProjectScanner = new MarketAppProjectScanner(GitHubUtils.extractActor(args),
          ScanUtils.getProceedRepo());
      status = marketAppProjectScanner.proceed();
      if (status != 0) {
        LOG.error("At least one issue is found during scanning");
      }
    } catch (Exception e) {
      LOG.error("Scan AppProject failed by {0}", e.getMessage());
      System.exit(1);
    }
    LOG.info("Scan AppProject completed");
    System.exit(status);
  }
}

