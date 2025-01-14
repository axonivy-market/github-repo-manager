package com.axonivy.github.scan;

import com.axonivy.github.DryRun;
import com.axonivy.github.GitHubProvider;
import com.axonivy.github.Logger;
import com.axonivy.github.constant.Constants;
import com.axonivy.github.scan.util.MavenUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.kohsuke.github.GHRepository;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static com.axonivy.github.constant.Constants.*;

public class MarketAppProjectScanner {
  private static final Logger LOG = new Logger();
  private static final String MAJOR_VERSION_REGEX = "\\.";
  private static final String MVN = "mvn ";
  private static final String MVN_WIN = "mvn.cmd ";
  private static final String MVN_CMD_PATTERN = "-f %s/pom.xml -Dmaven.test.skip=true -DaltDeploymentRepository=github::default::https://maven.pkg.github.com/%s";
  private static final String MAVEN_META_STATUS_PATTERN = "https://maven.axonivy.com/%s/maven-metadata.xml";
  private static final String DEPLOY_CMD = "--batch-mode deploy ";
  private static final String DEFAULT_BRANCH = "master";
  private final String ghActor;
  private final GHRepository repository;
  private final Set<String> appProjects;
  private int status;

  public MarketAppProjectScanner(String ghActor, String repoName) throws IOException {
    this.ghActor = ghActor;
    repository = GitHubProvider.getGithubByToken().getRepository(repoName);
    appProjects = new HashSet<>();
    status = 0;
  }

  public static Document getDocumentFromXMLContent(String xmlData) {
    try {
      DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      Document document = builder.parse(new InputSource(new StringReader(xmlData)));
      document.getDocumentElement().normalize();
      return document;
    } catch (Exception e) {
      LOG.error("Can not read the metadata of {0} with error {1}", xmlData, e);
    }
    return null;
  }

  public static void deleteDirectory(File file) throws IOException {
    Objects.requireNonNull(file);
    LOG.info("Delete {0}", file.getPath());
    try {
      FileUtils.deleteDirectory(file);
    } catch (IOException e) {
      LOG.error("Cannot delete directory {0}", file.getPath());
    } finally {
      FileUtils.deleteDirectory(file);
    }
  }

  private String findProductArtifactModule(Model pom) {
    String productArtifactModule = StringUtils.EMPTY;
    for (var module : pom.getModules()) {
      if (StringUtils.endsWithAny(module, APP_POSTFIX, DEMO_APP_POSTFIX)) {
        appProjects.add(module);
      }
      if (StringUtils.endsWith(module, Constants.PRODUCT_POSTFIX)) {
        // Use the last item
        productArtifactModule = module;
      }
    }
    return productArtifactModule;
  }

  private void executeMavenCommand(File projectDir, String goal) throws IOException, InterruptedException {
    String mavenCommand = MVN;
    if (SystemUtils.IS_OS_WINDOWS) {
      LOG.info("Running on WIN OS");
      mavenCommand = MVN_WIN;
    }
    mavenCommand = mavenCommand.concat(DEPLOY_CMD).concat(goal);
    LOG.info("Executing Maven command: {0}", mavenCommand);
    ProcessBuilder processBuilder = new ProcessBuilder(mavenCommand.split(StringUtils.SPACE));
    processBuilder.directory(projectDir);
    processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
    processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

    // Start the process and wait for finished
    Process process = processBuilder.start();
    int exitCode = process.waitFor();
    if (exitCode == 0) {
      LOG.info("Maven command executed successfully.");
    } else {
      LOG.error("Maven command failed with exit code: {0}", exitCode);
    }
  }

  public int proceed() throws IOException, InterruptedException, GitAPIException {
    File localRepoDir = new File(WORK_DIR + SLASH + repository.getName());
    deleteDirectory(localRepoDir);
    cloneRepository(localRepoDir);
    final String rootLocation = localRepoDir.getAbsolutePath();
    Model pom = readModulePom(rootLocation);
    if (pom == null) {
      status = 1;
      LOG.error("The {0} not a Maven project", rootLocation);
      return status;
    }

    String productArtifactModule = findProductArtifactModule(pom);
    if (StringUtils.isBlank(productArtifactModule)) {
      // Find in sub-folder
      for (var module : pom.getModules()) {
        Model pomItem = readModulePom(rootLocation + Constants.SLASH + module);
        if (pomItem == null) {
          LOG.info("No POM file at {0}", module);
          continue;
        }
        productArtifactModule = findProductArtifactModule(pomItem);
        if (StringUtils.isNoneBlank(productArtifactModule)) {
          checkAppArtifactsForTargetGroup(pomItem, productArtifactModule, localRepoDir);
        }
      }
    } else {
      checkAppArtifactsForTargetGroup(pom, productArtifactModule, localRepoDir);
    }
    return status;
  }

  private void checkAppArtifactsForTargetGroup(Model pom, String productArtifactModule, File localRepoDir)
      throws IOException, InterruptedException {
    var mavenURLs = StringUtils.replace(pom.getGroupId(), DOT, Constants.SLASH).concat(SLASH);
    Document productMetadataXML = getDocumentFromXMLContent(openStreamFromPath(String.format(MAVEN_META_STATUS_PATTERN, mavenURLs.concat(productArtifactModule))));
    if (productMetadataXML == null) {
      LOG.info("There is no product metadata-status available on maven repo");
      status = 1;
      return;
    }
    NodeList versionList = productMetadataXML.getElementsByTagName("version");
    List<String> proceedVersions = unifyVersionMustToRelease(versionList);

    for (var app : appProjects) {
      if (openStreamFromPath(String.format(MAVEN_META_STATUS_PATTERN, mavenURLs.concat(app))) == null) {
        LOG.info("No {0} artifact available on Maven repo", app);
        if (DryRun.is()) {
          LOG.info("DRY RUN: Missing an app artifact");
          status = 1;
          return;
        }

        deployNewAppArtifactToRepo(localRepoDir, app, proceedVersions);
      }
    }
  }

  private void deployNewAppArtifactToRepo(File localDir, String app, List<String> versions)
      throws IOException, InterruptedException {
    for (var version : versions) {
      LOG.info("Start building the {0} project with {1} version", app, version);
      updatePOMVersion(localDir, app, version);
      executeMavenCommand(localDir, MVN_CMD_PATTERN.formatted(app, repository.getFullName()));
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

  private List<String> unifyVersionMustToRelease(NodeList versionList) {
    List<String> versions = new ArrayList<>();
    for (int i = 0; i < versionList.getLength(); i++) {
      versions.add(versionList.item(i).getTextContent());
    }
    return versions.stream()
        .collect(Collectors.groupingBy(version -> version.split(MAJOR_VERSION_REGEX)[0]))
        .values().stream().map(Collections::max)
        .sorted().toList();
  }

  private String openStreamFromPath(String path) {
    try (var input = new URL(path).openStream()) {
      return new String(input.readAllBytes());
    } catch (FileNotFoundException | MalformedURLException e) {
      LOG.error("Cannot open URL {0} by {1}", path, e.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return null;
  }

  private Model readModulePom(String path) throws IOException {
    var pomFile = new File(path + SLASH + POM);
    try (var input = new FileInputStream(pomFile)) {
      return new MavenXpp3Reader().read(input);
    } catch (XmlPullParserException e) {
      LOG.error("Cannot read POM at {0} by {1}", path, e.getMessage());
    }
    return null;
  }

  private void cloneRepository(File directory) throws GitAPIException {
    LOG.info("Cloning repository...");
    Git git = Git.cloneRepository()
        .setURI(repository.getHtmlUrl().toString())
        .setDirectory(directory)
        .setCredentialsProvider(GitHubProvider.createCredentialFor(ghActor))
        .setBranch(DEFAULT_BRANCH)
        .call();
    LOG.info("Repository cloned from {0} into: {1} folder", DEFAULT_BRANCH, directory.toPath());
    git.close();
  }
}