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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import static com.axonivy.github.constant.Constants.*;

public class MarketAppProjectScanner {
  private static final Logger LOG = new Logger();
  private static final String MVN_CMD = "-f %s/pom.xml -Dmaven.test.skip=true -DaltDeploymentRepository=github::https://maven.pkg.github.com/%s deploy";
  private static final String MAVEN_REPO_PATTERN = "https://maven.axonivy.com/%s/maven-metadata.xml";
  private static final String DEFAULT_BRANCH = "master";
  private final String ghActor;
  private final GHRepository repository;
  private Map<String, List<String>> appProjectWithVersionMap = new HashMap<>();

  public MarketAppProjectScanner(String ghActor, String repoName) throws IOException {
    this.ghActor = ghActor;
    repository = GitHubProvider.getGithubByToken().getRepository(repoName);
  }

  public static Document getDocumentFromXMLContent(String xmlData) {
    Document document = null;
    try {
      DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      document = builder.parse(new InputSource(new StringReader(xmlData)));
      document.getDocumentElement().normalize();
    } catch (Exception e) {
      LOG.error("Metadata Reader: can not read the metadata of {0} with error {1}", xmlData, e);
    }
    return document;
  }

  private String findProductArtifact(Model pom) {
    String productArtifact = "";
    String groupId = pom.getGroupId();
    for (var module : pom.getModules()) {
      if (StringUtils.endsWithAny(module, APP_POSTFIX, DEMO_APP_POSTFIX)) {
        appProjectWithVersionMap.putIfAbsent(module, new ArrayList<>());
      }
      if (StringUtils.endsWith(module, Constants.PRODUCT_POSTFIX)) {
        productArtifact = module;
      }
    }
    return productArtifact;
  }

  private void runMavenCommand(File projectDir, String goals) throws IOException, InterruptedException {
    // Prepare the Maven command
    String mavenCommand = "mvn ";
    if (SystemUtils.IS_OS_WINDOWS) {
      LOG.info("Running on WIN OS");
      mavenCommand = "mvn.cmd ";
    }
    mavenCommand = mavenCommand.concat(goals).concat(" -B");
    // Use ProcessBuilder to execute the command
    ProcessBuilder processBuilder = new ProcessBuilder(mavenCommand.split(" "));
    processBuilder.directory(projectDir); // Set the working directory
    processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT); // Redirect output to console
    processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT); // Redirect error to console

    // Start the process
    Process process = processBuilder.start();
    int exitCode = process.waitFor(); // Wait for the process to complete

    // Check the result
    if (exitCode == 0) {
      LOG.info("Maven command executed successfully.");
    } else {
      System.err.println("Maven command failed with exit code: " + exitCode);
    }
  }

  public static void deleteDirectory(File file) throws IOException {
    try {
      FileUtils.deleteDirectory(file);
    } catch (IOException e) {
      LOG.error("Cannot delete directory {0}", file.getPath());
    } finally {
      FileUtils.deleteDirectory(file);
    }
  }

  public void proceed() {
    try {
      File localDir = new File("work/" + repository.getName());
      deleteDirectory(localDir);
      cloneRepository(localDir, null);
      Model pom = readModulePom(localDir.getAbsolutePath());
      String groupId = pom.getGroupId();
      String productArtifact = "";
      productArtifact = findProductArtifact(pom);
      if (StringUtils.isBlank(productArtifact)) {
        for (var module : pom.getModules()) {
          Model pomItem = readModulePom(localDir.getAbsolutePath() + Constants.SLASH + module);
          productArtifact = findProductArtifact(pomItem);
          if (StringUtils.isNoneBlank(productArtifact)) {
            checkAppArtifactForTargetGroup(pomItem, productArtifact, localDir);
          }
        }
      } else {
        checkAppArtifactForTargetGroup(pom, productArtifact, localDir);
      }
    } catch (Exception e) {
      LOG.error("Scan AppProject failed {0}", e.getMessage());
    }
  }

  private void checkAppArtifactForTargetGroup(Model pomItem, String productArtifact, File localDir) throws IOException, InterruptedException {
    String groupId = pomItem.getGroupId();
    var mavenURLs = StringUtils.replace(pomItem.getGroupId(), ".", Constants.SLASH).concat(SLASH);
    var productURL = mavenURLs.concat(productArtifact);

    for (var app: appProjectWithVersionMap.keySet()) {
      if (openStreamFromPath(String.format(MAVEN_REPO_PATTERN, mavenURLs.concat(app))) == null) {
        LOG.info("No {0} artifact available on Maven", app);
        if (DryRun.is()) {
          LOG.info("DRY RUN: Missing an app artifact");
          return;
        }
        Document metaXML = getDocumentFromXMLContent(openStreamFromPath(String.format(MAVEN_REPO_PATTERN, productURL)));
        NodeList versionList = metaXML.getElementsByTagName("version");
        List<String> proceedVersions = unifyVersionMustToRelease(versionList);
//        cloneRepository(localDir, null);
        for (var version : proceedVersions) {
          updatePOMVersion(localDir, app, version);
          // Step 4: Run Maven commands
          var manvenDeploy = MVN_CMD.formatted(app, repository.getFullName());
          runMavenCommand(localDir, manvenDeploy);
        }
      }
    }
  }

  private void updatePOMVersion(File localDir, String app, String version) throws IOException {
    Model pom = readModulePom(localDir.getPath() + SLASH + app);
    pom.setVersion(version);
    for (var dependency : pom.getDependencies()) {
      if (dependency.getType().equals(IAR) && dependency.getGroupId().equals(pom.getGroupId())) {
        dependency.setVersion(version);
      }
    }
    FileUtils.writeStringToFile(new File(localDir.getPath() + SLASH + app + SLASH + POM), MavenUtils.convertModelToString(pom), StandardCharsets.UTF_8);
  }

  private List<String> unifyVersionMustToRelease(NodeList versionList) {
    // Only covert 10.0.x /12.0.x
    List<String> version10 = new ArrayList<>();
    List<String> version12 = new ArrayList<>();
    for (int i = 0; i < versionList.getLength(); i++) {
      String version = versionList.item(i).getTextContent();
      if (StringUtils.startsWith(version, "10.0")) {
        version10.add(version);
      }
      if (StringUtils.startsWith(version, "12.0")) {
        version12.add(version);
      }
    }
    return List.of(version10.stream().max(Comparator.naturalOrder()).orElse(""), version12.stream().max(Comparator.naturalOrder()).orElse(""));
  }

  private String openStreamFromPath(String path) {
    try (var input = new URL(path).openStream()) {
      return new String(input.readAllBytes());
    } catch (FileNotFoundException | MalformedURLException e) {
      LOG.error("Cannot open URL {0} {1}", path, e.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return null;
  }

  private Model readModulePom(String path) throws IOException {
    var pomFile = new File(path + "/pom.xml");
    try (var input = new FileInputStream(pomFile)) {
      MavenXpp3Reader reader = new MavenXpp3Reader();
      return reader.read(input);
    } catch (XmlPullParserException e) {
      throw new RuntimeException(e);
    }
  }

  private void cloneRepository(File directory, String tagName) {
    LOG.info("Cloning repository...");
    try {
      Git git = Git.cloneRepository().setURI(repository.getHtmlUrl().toString()).setDirectory(directory).setCredentialsProvider(GitHubProvider.createCredentialFor(ghActor)).setBranch(DEFAULT_BRANCH).call();
      LOG.info("Repository cloned from {0} to: {1}", DEFAULT_BRANCH, directory.toPath());

      // Checkout the specific tag
      if (StringUtils.isNoneBlank(tagName)) {
        LOG.info("Checking out tag: {0}", tagName);
        git.checkout().setName(tagName).call();
        LOG.info("Checked out to tag: {0}", tagName);
      }
      git.close();

      LOG.info("Repository cloned to: " + directory.toPath());
    } catch (Exception e) {
      LOG.error("Cannot clone repo {0}", e.getMessage());
    }
  }
}

