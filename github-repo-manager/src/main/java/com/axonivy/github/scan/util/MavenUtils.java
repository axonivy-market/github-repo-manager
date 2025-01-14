package com.axonivy.github.scan.util;

import com.axonivy.github.Logger;
import com.axonivy.github.constant.Constants;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.axonivy.github.scan.enums.MavenProperty.*;
import static org.apache.commons.lang3.StringUtils.EMPTY;

public class MavenUtils {
  private static final Logger LOG = new Logger();
  private static final String KEY_START = "${";
  private static final String KEY_END = "}";
  private static final String KEY_PLACEHOLDER = KEY_START + "%s" + KEY_END;

  public static MavenModel findMavenModels(GHRepository repository) throws XmlPullParserException {
    Objects.requireNonNull(repository);
    LOG.info("Collect all POM files of {0}", repository.getName());
    GHContent pomRoot = getPomFileAtRootFolder(repository, EMPTY);
    Model pomRootModel = convertPomToModel(pomRoot);
    if (pomRootModel == null) {
      return null;
    }

    Set<Model> models = new HashSet<>();
    for (var module : pomRootModel.getModules()) {
      if (StringUtils.endsWithAny(module, Constants.TEST_POSTFIX, Constants.PRODUCT_POSTFIX)) {
        continue;
      }
      String resolvedModule = resolveMavenVariable(pomRootModel, module);
      GHContent pomItem = getPomFileAtRootFolder(repository, resolvedModule);
      Model modelItem = convertPomToModel(pomItem);
      if (modelItem == null) {
        continue;
      }
      for (var dependency : modelItem.getDependencies()) {
        if (Constants.IAR.equals(dependency.getType())) {
          models.add(modelItem);
        }
      }
    }
    return new MavenModel(pomRootModel, models);
  }

  private static Model convertPomToModel(GHContent pomContent) throws XmlPullParserException {
    if (pomContent == null) {
      return null;
    }
    try (var inputStream = pomContent.read()) {
      return new MavenXpp3Reader().read(inputStream);
    } catch (IOException e) {
      LOG.error("Cannot read data from GHContent {0}", e.getMessage());
    }
    return null;
  }

  public static AppProject createAssemblyAppProject(String productId, Model parentPom, List<Model> mavenModels)
      throws Exception {
    return new AppProject(generatePomContent(productId, parentPom, mavenModels),
        readResourceFile(Constants.ASSEMBLY),
        readResourceFile(Constants.DEPLOY_OPTIONS));
  }

  private static String resolveMavenVariable(Model pomModel, String property) {
    Properties properties = new Properties(pomModel.getProperties());
    properties.put(combineProperty(PROJECT, NAME), ObjectUtils.defaultIfNull(pomModel.getName(), EMPTY));
    properties.put(combineProperty(PROJECT, VERSION), ObjectUtils.defaultIfNull(pomModel.getVersion(), EMPTY));
    properties.put(combineProperty(PROJECT, GROUP_ID), ObjectUtils.defaultIfNull(pomModel.getGroupId(), EMPTY));
    properties.put(combineProperty(PROJECT, ARTIFACT_ID), ObjectUtils.defaultIfNull(pomModel.getArtifactId(), EMPTY));

    String resolvedValue = property;
    for (String key : properties.stringPropertyNames()) {
      String placeholder = KEY_PLACEHOLDER.formatted(key);
      if (resolvedValue.contains(placeholder)) {
        resolvedValue = resolvedValue.replace(placeholder, properties.getProperty(key));
        break;
      }
    }
    return resolvedValue;
  }

  private static GHContent getPomFileAtRootFolder(GHRepository repository, String path) {
    try {
      return repository.getFileContent(path + Constants.SLASH + Constants.POM);
    } catch (IOException e) {
      LOG.error("No POM file at path {0}", path);
    }
    return null;
  }

  private static String generatePomContent(String repoName, Model parentModel, List<Model> mavenModels)
      throws Exception {
    Model model;
    try (InputStream inputStream = MavenUtils.class.getResourceAsStream(Constants.POM)) {
      Objects.requireNonNull(inputStream, "Sample POM file not found in resources.");
      model = new MavenXpp3Reader().read(inputStream);
    }

    model.setGroupId(parentModel.getGroupId());
    model.setArtifactId(repoName + Constants.APP_POSTFIX);
    model.setVersion(parentModel.getVersion());
    model.setScm(parentModel.getScm());

    for (var mavenModel : mavenModels) {
      Dependency dependency = new Dependency();
      dependency.setGroupId(mavenModel.getGroupId());
      dependency.setArtifactId(mavenModel.getArtifactId());
      dependency.setVersion(mavenModel.getVersion());
      dependency.setType(Constants.IAR);
      model.addDependency(dependency);
    }
    return convertModelToString(model);
  }

  public static String convertModelToString(Model mavenModels) throws IOException {
    StringWriter writer = new StringWriter();
    new MavenXpp3Writer().write(writer, mavenModels);
    return writer.toString();
  }

  private static String readResourceFile(String fileName) {
    try (var resourceFile = MavenUtils.class.getResourceAsStream(fileName)) {
      Objects.requireNonNull(resourceFile);
      return new String(resourceFile.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException("Error reading resource file: " + fileName, e);
    }
  }

  public static String resolveNewModuleName(Model mavenModels, String moduleNamePostfix, String defaultModuleName) {
    String moduleName = defaultModuleName;
    for (String module : mavenModels.getModules()) {
      if (StringUtils.startsWith(module, KEY_START)) {
        var modulePrefix = module.substring(0, module.indexOf(KEY_END) + 1);
        moduleName = modulePrefix + moduleNamePostfix;
        break;
      }
    }
    return moduleName;
  }

  public record AppProject(String pom, String assembly, String deployOptions) {
  }

  public record MavenModel(Model pom, Set<Model> pomModules) {
  }
}
