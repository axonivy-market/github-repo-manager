package com.axonivy.github.scan;

import com.axonivy.github.DryRun;
import com.axonivy.github.GitHubProvider;
import com.axonivy.github.Logger;
import com.axonivy.github.constant.Constants;
import com.axonivy.github.scan.util.MavenUtils;
import com.axonivy.github.scan.util.ScanUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.kohsuke.github.GHRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.axonivy.github.constant.Constants.*;
import static org.apache.commons.lang3.StringUtils.EMPTY;

public class MarketAppProjectScanner {
  private static final Logger LOG = new Logger();
  private static final String MAJOR_VERSION_REGEX = "\\.";
  private static final String MAVEN_META_STATUS_PATTERN = "https://maven.axonivy.com/%s/maven-metadata.xml";
  private static final String DEFAULT_BRANCH = "master";
  private final String ghActor;
  private final GHRepository repository;
  private int status;

  public MarketAppProjectScanner(String ghActor, String repoName) throws IOException {
    this.ghActor = ghActor;
    repository = GitHubProvider.getGithubByToken().getRepository(repoName);
    status = 0;
  }

  private String findProductArtifactModule(Model pom) {
    Objects.requireNonNull(pom);
    return Optional.ofNullable(pom.getModules()).stream().flatMap(List::stream)
        .filter(module -> StringUtils.endsWith(module, Constants.PRODUCT_POSTFIX))
        .findAny().orElse(EMPTY);
  }

  private List<String> collectAppArtifactModules(Model pom) {
    Objects.requireNonNull(pom);
    return Optional.ofNullable(pom.getModules()).stream().flatMap(List::stream)
        .filter(module -> StringUtils.endsWithAny(module, APP_POSTFIX, DEMO_APP_POSTFIX))
        .distinct().toList();
  }

  public int proceed() throws IOException, InterruptedException, GitAPIException {
    File localRepoDir = cloneNewRepository();
    final String rootLocation = localRepoDir.getAbsolutePath();
    Model pom = readModulePom(rootLocation);
    if (pom == null) {
      status = 1;
      LOG.error("The {0} not a Maven project", rootLocation);
      return status;
    }

    String productArtifactModule = findProductArtifactModule(pom);
    if (StringUtils.isBlank(productArtifactModule)) {
      // Find in sub-folder, e.g: "axonivy-market/demo-projects"
      // Only proceed the second level, e.g: "axonivy-market/demo-projects/connectivity"
      for (var module : pom.getModules()) {
        Model pomItem = readModulePom(rootLocation + Constants.SLASH + module);
        if (pomItem == null) {
          LOG.info("No POM file at {0}", module);
          continue;
        }
        productArtifactModule = findProductArtifactModule(pomItem);
        if (StringUtils.isNoneBlank(productArtifactModule)) {
          checkAndReleaseNewAppArtifactsForTargetPOM(pomItem, productArtifactModule, localRepoDir);
        }
      }
    } else {
      checkAndReleaseNewAppArtifactsForTargetPOM(pom, productArtifactModule, localRepoDir);
    }
    return status;
  }

  /**
   * Read the maven metadata-status of product artifact.
   * Then check the available app project based on major version from product artifact.
   * If missing, crease a new release for highest major version.
   *
   * @param pom                   the pom file where the product artifact module was defined
   * @param productArtifactModule product module name of the product
   * @param localRepoDir          working directory of the repo
   */
  private void checkAndReleaseNewAppArtifactsForTargetPOM(Model pom, String productArtifactModule, File localRepoDir)
      throws IOException, InterruptedException {
    productArtifactModule = MavenUtils.resolveMavenVariable(pom, productArtifactModule);
    var mavenURLs = StringUtils.replace(pom.getGroupId(), DOT, SLASH).concat(SLASH);
    String productXMLResponse = openStreamFromPath(MavenUtils.getMetadataStatusURL(mavenURLs.concat(productArtifactModule)));
    List<String> mavenVersions = MavenUtils.getMavenVersionsFromXML(productXMLResponse);
    if (ObjectUtils.isEmpty(mavenVersions)) {
      LOG.info("There is no product version available on maven repo");
      status = 1;
      return;
    }
    List<String> proceedVersions = unifyVersionMustToRelease(mavenVersions);
    if (ObjectUtils.isEmpty(proceedVersions)) {
      LOG.info("No product version need to be adapt");
      return;
    }

    for (var app : collectAppArtifactModules(pom)) {
      String appMetaResponse = openStreamFromPath(String.format(MAVEN_META_STATUS_PATTERN, mavenURLs.concat(app)));
      if (StringUtils.isBlank(appMetaResponse)) {
        LOG.info("No {0} artifact available on Maven repo", app);
        if (DryRun.is()) {
          LOG.info("DRY RUN: Missing {0} artifact with {1} version(s). Please run with DRYRUN=false to update", app, proceedVersions);
          status = 1;
          return;
        }

        LOG.info("Start deploying the {0} artifact with {1} version(s) to Maven repo", app, proceedVersions);
        deployNewAppArtifactToRepo(localRepoDir, app, proceedVersions);
      }
    }
  }

  private void deployNewAppArtifactToRepo(File localDir, String app, List<String> versions)
      throws IOException, InterruptedException {
    for (var version : versions) {
      LOG.info("Start building the {0} project with {1} version", app, version);
      updatePOMVersion(localDir, app, version);
      int executedStatus = MavenUtils.executeMavenDeployCommand(localDir, app, repository.getFullName());
      status = executedStatus != 0 ? executedStatus : status;
    }
  }

  private void updatePOMVersion(File localDir, String app, String version) throws IOException {
    String appLocation = localDir.getPath() + SLASH + app;
    Model pom = readModulePom(appLocation);
    if (pom == null) {
      LOG.error("Cannot find the POM file for {0}", app);
      status = 1;
      return;
    }
    pom.setVersion(version);
    for (var dependency : pom.getDependencies()) {
      if (dependency.getType().equals(IAR) && StringUtils.equals(dependency.getGroupId(), pom.getGroupId())) {
        dependency.setVersion(version);
      }
    }
    FileUtils.writeStringToFile(new File(appLocation + SLASH + POM),
        MavenUtils.convertModelToString(pom),
        StandardCharsets.UTF_8);
  }

  private List<String> unifyVersionMustToRelease(List<String> versionList) {
    Integer[] configRangeVersions = ScanUtils.getVersionRange();
    return Optional.ofNullable(versionList).stream()
        .flatMap(List::stream)
        .filter(version -> {
          if (configRangeVersions.length == 0) {
            return true;
          }
          String[] parts = version.split(MAJOR_VERSION_REGEX);
          int major = Integer.parseInt(parts[0]);
          return major >= configRangeVersions[0] && major <= configRangeVersions[1];
        })
        .collect(Collectors.groupingBy(version -> version.split(MAJOR_VERSION_REGEX)[0]))
        .values().stream().map(Collections::max)
        .sorted().toList();
  }

  private String openStreamFromPath(String path) {
    try (var input = new URL(path).openStream()) {
      return new String(input.readAllBytes());
    } catch (FileNotFoundException | MalformedURLException e) {
      LOG.error("Cannot open URL {0}", e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return null;
  }

  private Model readModulePom(String path) throws IOException {
    var pomFile = new File(path + SLASH + POM);
    Model model = null;
    try (var input = new FileInputStream(pomFile)) {
      model = new MavenXpp3Reader().read(input);
    } catch (XmlPullParserException e) {
      LOG.error("Cannot read POM at {0} by {1}", path, e.getMessage());
    }
    return model;
  }

  /**
   * Check and clean the existing repo directory.
   * Then cloning the code from target repo.
   *
   * @return cloned repo directory
   * @throws GitAPIException When got a JGit issue
   * @throws IOException     When got a File IO issue
   */
  private File cloneNewRepository() throws GitAPIException, IOException {
    File localRepoDir = new File(WORK_DIR + SLASH + repository.getName());
    String localPath = localRepoDir.getPath();
    LOG.info("Delete {0} directory", localPath);
    try {
      FileUtils.deleteDirectory(localRepoDir);
    } catch (IOException e) {
      LOG.error("Cannot delete {0} directory", localPath);
    } finally {
      // Try to delete the root directory
      FileUtils.deleteDirectory(localRepoDir);
    }

    LOG.info("Cloning repository...");
    Git git = Git.cloneRepository()
        .setURI(repository.getHtmlUrl().toString())
        .setDirectory(localRepoDir)
        .setCredentialsProvider(GitHubProvider.createCredentialFor(ghActor))
        .setBranch(DEFAULT_BRANCH)
        .call();
    LOG.info("Repository cloned from {0} into: {1} folder", DEFAULT_BRANCH, localPath);
    git.close();
    return localRepoDir;
  }
}