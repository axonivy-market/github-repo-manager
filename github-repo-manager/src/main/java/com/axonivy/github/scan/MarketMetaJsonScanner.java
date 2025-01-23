package com.axonivy.github.scan;

import com.axonivy.github.GitHubProvider;
import com.axonivy.github.Logger;
import com.axonivy.github.constant.Constants;
import com.axonivy.github.scan.enums.MavenProperty;
import com.axonivy.github.scan.model.AppModel;
import com.axonivy.github.scan.model.CommitModel;
import com.axonivy.github.scan.model.MavenModel;
import com.axonivy.github.scan.model.PullRequestModel;
import com.axonivy.github.scan.util.MavenUtils;
import com.axonivy.github.util.GitHubUtils;
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

import static com.axonivy.github.constant.Constants.*;
import static com.axonivy.github.scan.ScanMetaJsonFiles.GITHUB_URL;

public class MarketMetaJsonScanner {
  private static final Logger LOG = new Logger();
  private static final String APP= "App";
  private static final String APP_NAME_PATTERN = "%s " + APP;
  private static final String DEMO = "Demo";
  private static final String DEMO_APP_NAME_PATTERN = "%s " + DEMO + StringUtils.SPACE + APP;
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

  public boolean process() throws Exception {
    anyChanges = false;
    for (GHContent content : gitHub.getRepository(marketRepo).getDirectoryContent(ROOT_FOLDER)) {
      if (isIgnoreRepo(content)) {
        LOG.info("Ignore folder: {0}", content.getName());
        continue;
      }
      findMetaPath(content);
    }
    return anyChanges;
  }

  private void findMetaPath(GHContent ghContent) throws Exception {
    if (ghContent.isFile()) {
      proceedMetaFile(ghContent);
      return;
    }
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
      var commitModel = new CommitModel();
      commitModel.setRepository(repository);
      commitModel.setBranch(BRANCH_NAME);
      commitModel.setPath(content.getPath());
      commitModel.setMessage(COMMIT_META_MESSAGE);
      commitModel.setContent(FileUtils.readFileToString(metaJsonFile, StandardCharsets.UTF_8));
      commitModel.setForce(true);
      GitHubUtils.commitFileChanges(commitModel);
      // Create a pull request
      var pullRequestModel = new PullRequestModel();
      pullRequestModel.setRepository(repository);
      pullRequestModel.setGhActor(ghActor);
      pullRequestModel.setBranch(BRANCH_NAME);
      pullRequestModel.setMessage(MISSING_MAVEN_ARTIFACT_MESSAGE);
      pullRequestModel.setTitle(COMMIT_MISSING_MAVEN_ARTIFACT_TITLE);
      GitHubUtils.createPullRequest(pullRequestModel);
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
    artifactIds.add(mavenModels.getPom().getArtifactId());
    artifactIds.addAll(mavenModels.getPomModules().stream().map(Model::getArtifactId).toList());

    String rootProductId = rootNode.get(MavenProperty.ID.key).asText();
    if (isRequiredAppArtifact(mavenModels, artifactIds, rootProductId, mavenArtifacts)) {
      LOG.info("Need to create a new {0}-app.zip project!", rootProductId);
      String appModule = createNewAppArtifact(metaJsonFile, mavenArtifacts, rootNode, mavenModels, false);
      mavenModels.getPom().getModules().add(appModule);
      modified = true;
    }
    if (isRequiredDemoAppArtifact(artifactIds, rootProductId, mavenArtifacts)) {
      LOG.info("Need to create a new {0}-demo-app.zip project!", rootProductId);
      String demoAppModule = createNewAppArtifact(metaJsonFile, mavenArtifacts, rootNode, mavenModels, true);
      mavenModels.getPom().getModules().add(demoAppModule);
      modified = true;
    }
    if (modified) {
      LOG.info("Update pom.xml for {0} to include new modules", productSource);
      GitHubUtils.createBranchIfMissing(repository, BRANCH_NAME);
      var commitModel = new CommitModel();
      commitModel.setRepository(repository);
      commitModel.setBranch(BRANCH_NAME);
      commitModel.setPath(POM);
      commitModel.setMessage(COMMIT_POM_MODULE_MESSAGE);
      commitModel.setContent(MavenUtils.convertModelToString(mavenModels.getPom()));
      commitModel.setForce(true);
      GitHubUtils.commitFileChanges(commitModel);

      var pullRequestModel = new PullRequestModel();
      pullRequestModel.setRepository(repository);
      pullRequestModel.setGhActor(ghActor);
      pullRequestModel.setBranch(BRANCH_NAME);
      pullRequestModel.setMessage(MISSING_MAVEN_ARTIFACT_MESSAGE);
      pullRequestModel.setTitle(COMMIT_MISSING_MAVEN_ARTIFACT_TITLE);
      GitHubUtils.createPullRequest(pullRequestModel);
    }
    return modified;
  }

  private boolean isRequiredDemoAppArtifact(List<String> artifactIds, String rootProductId, ArrayNode mavenArtifacts) {
    return artifactIds.stream().anyMatch(id -> StringUtils.endsWithAny(id, DEMO_POSTFIX, DEMOS_POSTFIX))
        && isMissingRequiredArtifact(rootProductId, mavenArtifacts, DEMO_APP_POSTFIX);
  }

  private boolean isRequiredAppArtifact(MavenModel mavenModels, List<String> artifactIds, String rootProductId, ArrayNode mavenArtifacts) {
    return !mavenModels.getPomModules().stream().filter(id -> !artifactIds.contains(id.getArtifactId())).toList().isEmpty()
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

  private String createNewAppArtifact(File metaJsonFile, ArrayNode mavenArtifacts, ObjectNode rootNode, MavenModel mavenModels, boolean isDemoApp)
      throws Exception {
    String artifactKey = rootNode.get(MavenProperty.ID.key).asText();
    String productName = rootNode.get(MavenProperty.NAME.key).asText();
    String artifactName = APP_NAME_PATTERN.formatted(productName);
    String artifactId = artifactKey.concat(APP_POSTFIX);
    List<Model> appMavenModels = null;
    if (isDemoApp) {
      if (productName.endsWith(DEMO)) {
        productName = StringUtils.removeEnd(productName, DEMO);
      }
      artifactName = productName.endsWith(DEMO) ?
          DEMO_APP_NAME_PATTERN.formatted(StringUtils.removeEnd(productName, DEMO)) : DEMO_APP_NAME_PATTERN.formatted(productName);
      artifactId = artifactKey.endsWith(DEMO_POSTFIX) ?
          StringUtils.removeEnd(artifactKey, DEMO_POSTFIX).concat(DEMO_APP_POSTFIX) : artifactKey.concat(DEMO_APP_POSTFIX);
      appMavenModels = mavenModels.getPomModules().stream().toList();
    } else {
      appMavenModels = mavenModels.getPomModules().stream()
          .filter(model -> !StringUtils.endsWith(model.getArtifactId(), DEMO_POSTFIX)).toList();
    }
    ObjectNode appArtifactNode = objectMapper.createObjectNode()
        .put(MavenProperty.KEY.key, artifactKey)
        .put(MavenProperty.NAME.key, artifactName)
        .put(MavenProperty.GROUP_ID.key, Objects.requireNonNull(mavenModels).getPom().getGroupId())
        .put(MavenProperty.ARTIFACT_ID.key, artifactId)
        .put(MavenProperty.TYPE.key, MavenProperty.TYPE.defaultValue);

    mavenArtifacts.add(appArtifactNode);
    writeJSONToFile(metaJsonFile, rootNode);

    // Create product-demo-app project
    createAssemblyAppProject(extractProductSource(rootNode), artifactId, mavenModels.getPom(), appMavenModels);
    return MavenUtils.resolveNewModuleName(mavenModels.getPom(), DEMO_APP_POSTFIX, artifactId);
  }

  private void createAssemblyAppProject(String ghRepoURL, String productId, Model parentPom, List<Model> mavenModels)
      throws Exception {
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
    String projectPath = productId + SLASH;
    AppModel appModel = MavenUtils.createAssemblyAppProject(productId, parentPom, mavenModels);
    var commitModel = new CommitModel();
    commitModel.setRepository(repository);
    commitModel.setBranch(BRANCH_NAME);
    commitModel.setPath(projectPath + POM);
    commitModel.setMessage(COMMIT_NEW_FILE_MESSAGE.formatted(POM));
    commitModel.setContent(appModel.getPom());
    commitModel.setForce(false);
    returnedStatus = GitHubUtils.commitFileChanges(commitModel);
    status = returnedStatus != 0 ? returnedStatus : status;

    commitModel.setPath(projectPath + ASSEMBLY);
    commitModel.setMessage(COMMIT_NEW_FILE_MESSAGE.formatted(ASSEMBLY));
    commitModel.setContent(appModel.getAssembly());
    returnedStatus = GitHubUtils.commitFileChanges(commitModel);
    status = returnedStatus != 0 ? returnedStatus : status;

    commitModel.setPath(projectPath + DEPLOY_OPTIONS);
    commitModel.setMessage(COMMIT_NEW_FILE_MESSAGE.formatted(DEPLOY_OPTIONS));
    commitModel.setContent(appModel.getDeployOptions());
    returnedStatus = GitHubUtils.commitFileChanges(commitModel);
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