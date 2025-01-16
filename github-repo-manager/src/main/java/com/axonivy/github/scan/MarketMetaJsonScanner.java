package com.axonivy.github.scan;

import com.axonivy.github.GitHubProvider;
import com.axonivy.github.Logger;
import com.axonivy.github.constant.Constants;
import com.axonivy.github.scan.enums.MavenProperty;
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
  private static final String APP_NAME_PATTERN = "%s App";
  private static final String DEMO_APP_NAME_PATTERN = "%s Demo App";
  private static final String ROOT_FOLDER = "market";
  private static final String META_JSON = "meta.json";
  private static final String BRANCH_NAME = "MARP-1872-Export-MarketPlace-Components-from-Designer-with-Dependencies";
  private static final String COMMIT_META_MESSAGE = "Add missing Maven artifact blocks to meta.json files";
  public static final String COMMIT_POM_MODULE_MESSAGE = "Update POM module";
  public static final String COMMIT_MISSING_MAVEN_ARTIFACT_TITLE = "Add missing Maven artifact blocks";
  public static final String MISSING_MAVEN_ARTIFACT_MESSAGE = "This PR adds missing Maven artifact blocks to all `meta.json` files in the repository.";
  public static final String COMMIT_NEW_FILE_MESSAGE = "Created %s file";
  private final ObjectMapper objectMapper;
  private final GitHub gitHub;
  private final GHUser ghActor;
  private final String marketRepo;
  private final List<String> ignoreRepos;
  private boolean anyChanges;

  public MarketMetaJsonScanner(String user, String marketRepo, List<String> ignoreRepos) throws IOException {
    Objects.requireNonNull(marketRepo);
    this.objectMapper = new ObjectMapper();
    this.gitHub = GitHubProvider.getGithubByToken();
    this.ghActor = gitHub.getUser(user);
    this.marketRepo = marketRepo;
    this.ignoreRepos = ignoreRepos;
  }

  private static void downloadFile(GHContent content, File targetFile) throws IOException {
    try (InputStream inputStream = new URL(content.getDownloadUrl()).openStream()) {
      FileUtils.copyInputStreamToFile(inputStream, targetFile);
    }
  }

  private void writeJSONToFile(File metaJsonFile, ObjectNode rootNode) throws IOException {
    DefaultPrettyPrinter.Indenter indenter = new DefaultIndenter("    ", DefaultIndenter.SYS_LF);
    DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
    printer.indentObjectsWith(indenter);
    printer.indentArraysWith(indenter);
    objectMapper.writer(printer).writeValue(metaJsonFile, rootNode);
  }

  public int process() throws Exception {
    anyChanges = false;
    for (GHContent content : gitHub.getRepository(marketRepo).getDirectoryContent(ROOT_FOLDER)) {
      if (isIgnoreRepo(content)) {
        LOG.info("Ignore folder: {0}", content.getName());
        continue;
      }

      if (content.isDirectory()) {
        findMetaPath(content);
      } else {
        proceedMetaFile(content);
      }
    }
    return anyChanges ? 1 : 0;
  }

  private void findMetaPath(GHContent ghContent) throws Exception {
    for (var content : ghContent.listDirectoryContent()) {
      if (content.isDirectory()) {
        if (isIgnoreRepo(content)) {
          LOG.info("Ignore folder: {0}", content.getName());
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
    LOG.info("Proceed {0}", content.getPath());
    // Download the meta.json file from the subfolder
    File metaJsonFile = new File(Constants.WORK_DIR.concat(SLASH).concat(content.getPath()));
    downloadFile(content, metaJsonFile);

    boolean modified = modifyMetaJsonFile(metaJsonFile);
    anyChanges = modified || anyChanges;
    if (anyChanges) {
      LOG.info("Update meta.json for {0} to include app project", content.getPath());
      GHRepository repository = gitHub.getRepository(marketRepo);
      // Create a new branch
      GitHubUtils.createBranchIfMissing(repository, BRANCH_NAME);
      // Commit changes
      GitHubUtils.commitNewFile(repository, BRANCH_NAME, content.getPath(), COMMIT_META_MESSAGE,
          FileUtils.readFileToString(metaJsonFile, StandardCharsets.UTF_8));
      // Create a pull request
      GitHubUtils.createPullRequest(ghActor, repository, BRANCH_NAME, COMMIT_MISSING_MAVEN_ARTIFACT_TITLE,
          MISSING_MAVEN_ARTIFACT_MESSAGE);
    } else {
      LOG.info("No changes were necessary.");
    }
  }

  private boolean modifyMetaJsonFile(File metaJsonFile) throws Exception {
    ObjectNode rootNode = (ObjectNode) objectMapper.readTree(metaJsonFile);
    ArrayNode mavenArtifacts = (ArrayNode) rootNode.get(MavenProperty.ROOT.key);
    // Skip the file if mavenArtifacts is empty
    if (mavenArtifacts == null || mavenArtifacts.isEmpty()) {
      LOG.info("Skipping: mavenArtifacts is empty in " + metaJsonFile.getPath());
      return false;
    }

    boolean modified = false;
    String productSource = extractProductSource(rootNode);
    GHRepository repository = gitHub.getRepository(productSource);
    MavenModel mavenModels = MavenUtils.findMavenModels(repository);
    Objects.requireNonNull(mavenModels);
    List<String> artifactIds = new ArrayList<>();
    artifactIds.add(mavenModels.pom().getArtifactId());
    artifactIds.addAll(mavenModels.pomModules().stream().map(Model::getArtifactId).toList());

    String rootProductId = rootNode.get(MavenProperty.ID.key).asText();
    if (isRequiredAppArtifact(mavenModels, artifactIds, rootProductId, mavenArtifacts)) {
      LOG.info("Need to create a new {0}-app.zip project!", rootProductId);
      String appModule = createNewAppArtifact(metaJsonFile, mavenArtifacts, rootNode, mavenModels);
      mavenModels.pom().getModules().add(appModule);
      modified = true;
    }
    if (isRequiredDemoAppArtifact(artifactIds, rootProductId, mavenArtifacts)) {
      LOG.info("Need to create a new {0}-demo-app.zip project!", rootProductId);
      String demoAppModule = createNewDemoAppArtifact(metaJsonFile, mavenArtifacts, rootNode, mavenModels);
      mavenModels.pom().getModules().add(demoAppModule);
      modified = true;
    }
    if (modified) {
      LOG.info("Update pom.xml for {0} to include new modules", productSource);
      GitHubUtils.createBranchIfMissing(repository, BRANCH_NAME);
      GitHubUtils.commitNewFile(repository, BRANCH_NAME, POM, COMMIT_POM_MODULE_MESSAGE, MavenUtils.convertModelToString(mavenModels.pom()));
      GitHubUtils.createPullRequest(ghActor, repository, BRANCH_NAME, COMMIT_MISSING_MAVEN_ARTIFACT_TITLE, MISSING_MAVEN_ARTIFACT_MESSAGE);
    }
    return modified;
  }

  private boolean isRequiredDemoAppArtifact(List<String> artifactIds, String rootProductId, ArrayNode mavenArtifacts) {
    return artifactIds.stream().anyMatch(id -> StringUtils.endsWithAny(id, DEMO_POSTFIX, DEMOS_POSTFIX))
        && isMissingRequiredArtifact(rootProductId, mavenArtifacts, DEMO_APP_POSTFIX);
  }

  private boolean isRequiredAppArtifact(MavenModel mavenModels, List<String> artifactIds, String rootProductId, ArrayNode mavenArtifacts) {
    return !mavenModels.pomModules().stream().filter(id -> !artifactIds.contains(id.getArtifactId())).toList().isEmpty()
        && isMissingRequiredArtifact(rootProductId, mavenArtifacts, APP_POSTFIX);
  }

  private static String extractProductSource(ObjectNode rootNode) {
    Objects.requireNonNull(rootNode);
    String productSource = rootNode.get(MavenProperty.SOURCE_URL.key).asText();
    if (StringUtils.startsWith(productSource, GITHUB_URL)) {
      productSource = StringUtils.replace(productSource, GITHUB_URL, StringUtils.EMPTY);
    }
    return productSource;
  }

  private String createNewDemoAppArtifact(File metaJsonFile, ArrayNode mavenArtifacts, ObjectNode rootNode, MavenModel mavenModels)
      throws Exception {
    String productSource = extractProductSource(rootNode);
    String productGroupId = Objects.requireNonNull(mavenModels).pom().getGroupId();
    String productId = rootNode.get(MavenProperty.ID.key).asText();
    String productName = rootNode.get(MavenProperty.NAME.key).asText();
    ObjectNode appArtifactNode = objectMapper.createObjectNode()
        .put(MavenProperty.KEY.key, productId)
        .put(MavenProperty.NAME.key, DEMO_APP_NAME_PATTERN.formatted(productName))
        .put(MavenProperty.GROUP_ID.key, productGroupId)
        .put(MavenProperty.ARTIFACT_ID.key, productId.concat(DEMO_APP_POSTFIX))
        .put(MavenProperty.TYPE.key, MavenProperty.TYPE.defaultValue);

    mavenArtifacts.add(appArtifactNode);
    writeJSONToFile(metaJsonFile, rootNode);
    // Create product-demo-app project
    createAssemblyAppProject(productSource, productId + DEMO_POSTFIX,
        mavenModels.pom(), mavenModels.pomModules().stream().toList());
    return MavenUtils.resolveNewModuleName(mavenModels.pom(), DEMO_APP_POSTFIX, productId.concat(DEMO_APP_POSTFIX));
  }

  private String createNewAppArtifact(File metaJsonFile, ArrayNode mavenArtifacts, ObjectNode rootNode, MavenModel mavenModels)
      throws Exception {
    String productSource = extractProductSource(rootNode);
    String productGroupId = Objects.requireNonNull(mavenModels).pom().getGroupId();
    String productId = rootNode.get(MavenProperty.ID.key).asText();
    String productName = rootNode.get(MavenProperty.NAME.key).asText();

    ObjectNode appArtifactNode = objectMapper.createObjectNode().
        put(MavenProperty.KEY.key, productId).put(MavenProperty.NAME.key, APP_NAME_PATTERN.formatted(productName))
        .put(MavenProperty.GROUP_ID.key, productGroupId)
        .put(MavenProperty.ARTIFACT_ID.key, productId.concat(APP_POSTFIX))
        .put(MavenProperty.TYPE.key, MavenProperty.TYPE.defaultValue);

    mavenArtifacts.add(appArtifactNode);
    writeJSONToFile(metaJsonFile, rootNode);
    // Create product-app project
    createAssemblyAppProject(productSource, productId, mavenModels.pom(),
        mavenModels.pomModules().stream().filter(model -> !StringUtils.endsWith(model.getArtifactId(), DEMO_POSTFIX)).toList());
    return MavenUtils.resolveNewModuleName(mavenModels.pom(), APP_POSTFIX, productId.concat(APP_POSTFIX));
  }

  private void createAssemblyAppProject(String ghRepoURL, String productId, Model parentPom, List<Model> mavenModels) throws Exception {
    Objects.requireNonNull(ghRepoURL);
    int status = 0;
    if (StringUtils.startsWith(ghRepoURL, GITHUB_URL)) {
      ghRepoURL = StringUtils.replace(ghRepoURL, GITHUB_URL, "");
    }
    LOG.info("Create an Assembly App Project for {0}", ghRepoURL);
    GHRepository repository = gitHub.getRepository(ghRepoURL);
    int returnedStatus = GitHubUtils.createBranchIfMissing(repository, BRANCH_NAME);
    status = returnedStatus != 0 ? returnedStatus : status;

    // Create the project folder
    String projectPath = productId + APP_POSTFIX + SLASH;
    AppProject appProject = MavenUtils.createAssemblyAppProject(productId, parentPom, mavenModels);
    returnedStatus = GitHubUtils.commitNewFile(repository, BRANCH_NAME, projectPath + POM, COMMIT_NEW_FILE_MESSAGE.formatted(POM), appProject.pom());
    status = returnedStatus != 0 ? returnedStatus : status;
    returnedStatus = GitHubUtils.commitNewFile(repository, BRANCH_NAME, projectPath + ASSEMBLY, COMMIT_NEW_FILE_MESSAGE.formatted(ASSEMBLY), appProject.assembly());
    status = returnedStatus != 0 ? returnedStatus : status;
    returnedStatus = GitHubUtils.commitNewFile(repository, BRANCH_NAME, projectPath + DEPLOY_OPTIONS, COMMIT_NEW_FILE_MESSAGE.formatted(DEPLOY_OPTIONS), appProject.deployOptions());
    status = returnedStatus != 0 ? returnedStatus : status;
    if (status == 0) {
      LOG.info("Project created successfully inside the {0} repository!", ghRepoURL);
    } else {
      LOG.error("Had exception during create project for the {0} repository!", ghRepoURL);
    }
  }

  private boolean isMissingRequiredArtifact(String productId, ArrayNode mavenArtifacts, String postfix) {
    boolean missingNode = true;
    var requiredArtifactId = productId.concat(postfix);
    for (JsonNode artifact : mavenArtifacts) {
      var key = artifact.get(MavenProperty.KEY.key);
      var artifactId = artifact.get(MavenProperty.ARTIFACT_ID.key);

      if (key != null && StringUtils.equals(key.asText(), productId)
          && artifactId != null && StringUtils.equals(artifactId.asText(), requiredArtifactId)) {
        missingNode = false;
        break;
      }
    }
    return missingNode;
  }
}