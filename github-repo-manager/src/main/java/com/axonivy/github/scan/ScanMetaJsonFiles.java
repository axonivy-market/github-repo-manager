package com.axonivy.github.scan;

import com.axonivy.github.Logger;
import com.axonivy.github.util.GitHubUtils;
import org.apache.commons.lang3.StringUtils;

import static com.axonivy.github.scan.util.ScanUtils.getIgnoreRepos;
import static com.axonivy.github.scan.util.ScanUtils.getProceedRepo;

public class ScanMetaJsonFiles {
  private static final Logger LOG = new Logger();
  public static final String GITHUB_URL = "https://github.com/";
  private static final String MARKET_REPO = "axonivy-market/market";

  public static void main(String[] args) throws Exception {
    String proceedRepo = getProceedRepo();
    if (StringUtils.isBlank(proceedRepo)) {
      proceedRepo = MARKET_REPO;
    }
    LOG.info("Start Scanning Meta JSON files for {0} repo", proceedRepo);
    var marketMetaJsonScanner = new MarketMetaJsonScanner(GitHubUtils.extractActor(args), proceedRepo, getIgnoreRepos());
    boolean anyChanges = marketMetaJsonScanner.process();
    if (anyChanges) {
      LOG.error("At least one repo is out of date or an issue appears during scanning");
      System.exit(1);
    }
    LOG.info("Scanning Meta JSON files finished");
    System.exit(0);
  }
}

