package com.axonivy.github.scan;

import com.axonivy.github.DryRun;
import com.axonivy.github.GitHubProvider;
import com.axonivy.github.Logger;
import com.axonivy.github.scan.enums.MavenArtifactProperty;
import com.axonivy.github.util.GitHubUtils;
import com.axonivy.github.scan.util.MavenUtils;
import com.axonivy.github.scan.util.MavenUtils.AppProject;
import com.axonivy.github.scan.util.MavenUtils.MavenModel;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.axonivy.github.scan.ScanMetaJsonFiles.GITHUB_URL;
import static com.axonivy.github.constant.Constants.*;

public class MarketMetaJsonScanner {
  private static final Logger LOG = new Logger();

  private static final String WORK_FOLDER = "work";
  private static final String MARKET_FOLDER_PATH = "market";
  private static final String META_JSON = "meta.json";
  private static final String BRANCH_NAME = "fix-missing-maven-artifacts";
  private static final String COMMIT_MESSAGE = "Fix: Add missing Maven artifact blocks to meta.json files";
  private static final String APP_NAME_PATTERN = "%s App";
  private static final String DEMO_APP_NAME_PATTERN = "%s Demo App";
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private final GitHub gitHub;
  private final GHUser ghActor;
  private final String marketRepo;
  private final List<String> ignoreRepos;
  private boolean anyChanges;

  public MarketMetaJsonScanner(String user, String marketRepo, List<String> ignoreRepos) throws IOException {
    Objects.requireNonNull(marketRepo);
    this.gitHub = GitHubProvider.getGithubByToken();
    this.ghActor = gitHub.getUser(user);
    this.marketRepo = marketRepo;
    this.ignoreRepos = ignoreRepos;
  }

  private static void downloadFile(GHContent content, File targetFile) throws IOException {
    URL url = new URL(content.getDownloadUrl());
    try (InputStream inputStream = url.openStream()) {
      FileUtils.copyInputStreamToFile(inputStream, targetFile);
    }
    LOG.info("Downloaded: {0}", targetFile.getAbsolutePath());
  }

  private static void writeJSONToFile(File metaJsonFile, ObjectNode rootNode) throws IOException {
    DefaultPrettyPrinter.Indenter indenter = new DefaultIndenter("    ", DefaultIndenter.SYS_LF);
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    printer.indentObjectsWith(indenter);
    printer.indentArraysWith(indenter);
    objectMapper.writer(printer).writeValue(metaJsonFile, rootNode);
  }

  public int process() throws Exception {
    anyChanges = false;
    for (GHContent content : gitHub.getRepository(marketRepo).getDirectoryContent(MARKET_FOLDER_PATH)) {
      LOG.info("Proceed {0}", content.getName());
      if (isIgnoreRepo(content)) {
        continue;
      }

      if (content.isDirectory()) {
        findMetaPath(content);
      } else {
        proceedMetaFile(content);
      }
    }
    return 0;
  }

  private void findMetaPath(GHContent ghContent) throws Exception {
    for (var content : ghContent.listDirectoryContent()) {
      if (content.isDirectory()) {
        LOG.info("Proceed {0} product", content.getName());
        if (isIgnoreRepo(content)) {
          continue;
        }
        findMetaPath(content);
      } else {
        proceedMetaFile(content);
      }
    }
  }

  private boolean isIgnoreRepo(GHContent content) {
    String repoName = content.getName();
    if (Objects.nonNull(ignoreRepos)
        && ignoreRepos.stream().anyMatch(repo -> StringUtils.equalsIgnoreCase(repo, repoName))) {
      LOG.info("Repo {0} is ignored due to the config -DGITHUB.MARKET.IGNORE.REPOS", repoName);
      return true;
    }
    return false;
  }

  private void proceedMetaFile(GHContent content) throws Exception {
    if (!content.isFile() || !META_JSON.equals(content.getName())) {
      return;
    }
    // Download the meta.json file from the subfolder
    File metaJsonFile = new File(WORK_FOLDER.concat("/").concat(content.getPath()));
    downloadFile(content, metaJsonFile);

    boolean modified = modifyMetaJsonFile(metaJsonFile);
    anyChanges = modified || anyChanges;
    if (anyChanges) {
      if (DryRun.is()) {
        LOG.info("DRY RUN: ");
      } else {
        GHRepository repository = gitHub.getRepository(marketRepo);
        // Create a new branch
        GitHubUtils.createBranchIfMissing(repository, BRANCH_NAME);
        // Commit changes
        GitHubUtils.commitNewFile(repository, BRANCH_NAME, content.getPath(), COMMIT_MESSAGE, FileUtils.readFileToString(metaJsonFile, StandardCharsets.UTF_8));
        // Create a pull request
        GitHubUtils.createPullRequest(ghActor, repository, BRANCH_NAME, "Fix: Add missing Maven artifact blocks", "This PR adds missing Maven artifact blocks to all `meta.json` files in the repository.");
      }
    } else {
      LOG.info("No changes were necessary.");
    }
  }

  private boolean modifyMetaJsonFile(File metaJsonFile) throws Exception {
    ObjectNode rootNode = (ObjectNode) objectMapper.readTree(metaJsonFile);
    ArrayNode mavenArtifacts = (ArrayNode) rootNode.get(MavenArtifactProperty.ROOT.key);

    // Skip the file if mavenArtifacts is empty
    if (mavenArtifacts == null || mavenArtifacts.isEmpty()) {
      LOG.info("Skipping: mavenArtifacts is empty in " + metaJsonFile.getPath());
      return false;
    }

    boolean modified = false;
    // Check Maven dependencies
    String productSource = rootNode.get(MavenArtifactProperty.SOURCE_URL.key).asText();
    Objects.requireNonNull(productSource);
    if (StringUtils.startsWith(productSource, GITHUB_URL)) {
      productSource = StringUtils.replace(productSource, GITHUB_URL, "");
    }
    GHRepository repository = gitHub.getRepository(productSource);
    MavenModel mavenModels = MavenUtils.findMavenModels(repository);
    String productGroupId = Objects.requireNonNull(mavenModels).pom().getGroupId();
    List<String> artifactIds = new ArrayList<>();
    artifactIds.add(mavenModels.pom().getArtifactId());
    artifactIds.addAll(mavenModels.pomModules().stream().map(Model::getArtifactId).toList());

    var isNeedAnAppZip = !mavenModels.pomModules().stream().filter(id -> !artifactIds.contains(id.getArtifactId())).toList().isEmpty();
    List<String> newModules = new ArrayList<>();
    String productId = rootNode.get(MavenArtifactProperty.ID.key).asText();
    String productName = rootNode.get(MavenArtifactProperty.NAME.key).asText();
    if (isNeedAnAppZip && isMissingAppArtifact(productId, mavenArtifacts, APP_POSTFIX)) {
      ObjectNode appArtifactNode = objectMapper.createObjectNode().put(MavenArtifactProperty.KEY.key, productId).put(MavenArtifactProperty.NAME.key, APP_NAME_PATTERN.formatted(productName)).put(MavenArtifactProperty.GROUP_ID.key, productGroupId).put(MavenArtifactProperty.ARTIFACT_ID.key, productId.concat(APP_POSTFIX)).put(MavenArtifactProperty.TYPE.key, MavenArtifactProperty.TYPE.defaultValue);

      mavenArtifacts.add(appArtifactNode);
      writeJSONToFile(metaJsonFile, rootNode);
      // Create product-app project
      createAssemblyAppProject(productSource, productId, mavenModels.pom(), mavenModels.pomModules().stream().filter(model -> !StringUtils.endsWith(model.getArtifactId(), "-demo")).toList());
      String appModule = MavenUtils.resolveNewModuleName(mavenModels.pom(), APP_POSTFIX, productId.concat(APP_POSTFIX));
      newModules.add(appModule);
      modified = true;
    }
    if (artifactIds.stream().anyMatch(id -> StringUtils.endsWithAny(id, "-demo", "-demos")) && isMissingAppArtifact(productId, mavenArtifacts, DEMO_APP_POSTFIX)) {
      ObjectNode appArtifactNode = objectMapper.createObjectNode().put(MavenArtifactProperty.KEY.key, productId).put(MavenArtifactProperty.NAME.key, DEMO_APP_NAME_PATTERN.formatted(productName)).put(MavenArtifactProperty.GROUP_ID.key, productGroupId).put(MavenArtifactProperty.ARTIFACT_ID.key, productId.concat(DEMO_APP_POSTFIX)).put(MavenArtifactProperty.TYPE.key, MavenArtifactProperty.TYPE.defaultValue);

      mavenArtifacts.add(appArtifactNode);
      writeJSONToFile(metaJsonFile, rootNode);
      // Create product-demo-app project
      createAssemblyAppProject(productSource, productId + "-demo", mavenModels.pom(), mavenModels.pomModules().stream().toList());
      String demoAppModule = MavenUtils.resolveNewModuleName(mavenModels.pom(), DEMO_APP_POSTFIX, productId.concat(DEMO_APP_POSTFIX));
      newModules.add(demoAppModule);
      modified = true;
    }
    mavenModels.pom().getModules().addAll(newModules);

    GitHubUtils.createBranchIfMissing(repository, BRANCH_NAME);
    GitHubUtils.commitNewFile(repository, BRANCH_NAME, "pom.xml", "Update POM module", MavenUtils.convertModelToString(mavenModels.pom()));
    GitHubUtils.createPullRequest(ghActor, repository, BRANCH_NAME, "Fix: Add missing Maven artifact blocks", "This PR adds missing Maven artifact blocks to all `meta.json` files in the repository.");
    return modified;
  }

  private void createAssemblyAppProject(String ghRepoURL, String productId, Model parentPom, List<Model> mavenModels) throws Exception {
    Objects.requireNonNull(ghRepoURL);
    if (StringUtils.startsWith(ghRepoURL, GITHUB_URL)) {
      ghRepoURL = StringUtils.replace(ghRepoURL, GITHUB_URL, "");
    }
    LOG.info("Create an Assembly App Project for {0}", ghRepoURL);
    GHRepository repository = gitHub.getRepository(ghRepoURL);
    GitHubUtils.createBranchIfMissing(repository, BRANCH_NAME);

    // Create the project folder
    String projectPath = productId + "-app" + "/";
    AppProject appProject = MavenUtils.createAssemblyAppProject(productId, parentPom, mavenModels);
    GitHubUtils.commitNewFile(repository, BRANCH_NAME, projectPath + "pom.xml", "Created new file", appProject.pom());
    GitHubUtils.commitNewFile(repository, BRANCH_NAME, projectPath + "assembly.xml", "Created new file", appProject.assembly());
    GitHubUtils.commitNewFile(repository, BRANCH_NAME, projectPath + "deploy.options.yaml", "Created new file", appProject.deployOptions());
    LOG.info("Project created successfully inside the repository!");
  }

  private boolean isMissingAppArtifact(String productId, ArrayNode mavenArtifacts, String postfix) {
    boolean missingNode = true;
    for (JsonNode artifact : mavenArtifacts) {
      var key = artifact.get(MavenArtifactProperty.KEY.key);
      var artifactId = artifact.get(MavenArtifactProperty.ARTIFACT_ID.key);

      if (key != null && key.asText().equals(productId) && artifactId != null && artifactId.asText().equals(productId.concat(postfix))) {
        missingNode = false;
        break;
      }
    }
    return missingNode;
  }
}