package com.axonivy.github.scan;

import com.axonivy.github.Logger;
import com.axonivy.github.util.GitHubUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.*;
import java.util.Arrays;
import java.util.List;

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
    int status = marketMetaJsonScanner.process();
    if (status != 0) {
      LOG.error("At least one issue is found during scanning");
    }
    LOG.info("Scanning Meta JSON files finished");
    System.exit(status);
  }
}
