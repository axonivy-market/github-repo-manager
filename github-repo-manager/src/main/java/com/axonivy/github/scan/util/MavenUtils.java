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

public class MavenUtils {
  private static final String APP = "-app";
  private static final Logger LOG = new Logger();
  private static final String TEST_MODULE = "-test";
  private static final String PRODUCT_MODULE = "-product";
  private static final String IAR = "iar";
  public static final String POM = "pom.xml";

  public static MavenModel findMavenModels(GHRepository repository) throws XmlPullParserException {
    Objects.requireNonNull(repository);
    LOG.info("Collect all POM files of {0}", repository.getName());
    var mavenReader = new MavenXpp3Reader();
    GHContent pomRoot = getPomFileAtRootFolder(repository, "");
    Model pomRootModel = convertPomToModel(pomRoot);
    if (pomRootModel == null) {
      return null;
    }

    Set<Model> models = new HashSet<>();
    for (var module : pomRootModel.getModules()) {
      if (StringUtils.endsWithAny(module, TEST_MODULE, PRODUCT_MODULE)) {
        continue;
      }
      String resolvedModule = resolveMavenVariable(pomRootModel, module);
      GHContent pomItem = getPomFileAtRootFolder(repository, resolvedModule);
      Model itemModel = convertPomToModel(pomItem);
      if (itemModel == null) {
        continue;
      }
      for (var dependency : itemModel.getDependencies()) {
        if (IAR.equals(dependency.getType())) {
          models.add(itemModel);
        }
      }
    }
    return new MavenModel(pomRootModel, models);
  }

  private static Model convertPomToModel(GHContent pomContent) throws XmlPullParserException {
    if (pomContent == null) {
      return null;
    }
    var mavenReader = new MavenXpp3Reader();
    try (var inputStream = pomContent.read()) {
      return mavenReader.read(inputStream);
    } catch (IOException e) {
      LOG.error("Cannot read data from GHContent {0}", e.getMessage());
    }
    return null;
  }

  public static AppProject createAssemblyAppProject(String productId, Model parentPom, List<Model> mavenModels) throws Exception {
    return new AppProject(generatePomContent(productId, parentPom, mavenModels), readResourceFile("assembly.xml"), readResourceFile("deploy.options.yaml"));
  }

  private static String resolveMavenVariable(Model pomModel, String property) {
    Properties properties = new Properties(pomModel.getProperties());
    properties.put("project.name", ObjectUtils.defaultIfNull(pomModel.getName(), ""));
    properties.put("project.version", ObjectUtils.defaultIfNull(pomModel.getVersion(), ""));
    properties.put("project.groupId", ObjectUtils.defaultIfNull(pomModel.getGroupId(), ""));
    properties.put("project.artifactId", ObjectUtils.defaultIfNull(pomModel.getArtifactId(), ""));

    String resolvedValue = property;
    for (String key : properties.stringPropertyNames()) {
      String placeholder = "${" + key + "}";
      if (resolvedValue.contains(placeholder)) {
        resolvedValue = resolvedValue.replace(placeholder, properties.getProperty(key));
      }
    }
    return resolvedValue;
  }

  private static GHContent getPomFileAtRootFolder(GHRepository repository, String path) {
    try {
      return repository.getFileContent(path + Constants.SLASH + POM);
    } catch (IOException e) {
      LOG.error("No POM file at path {0}", path);
    }
    return null;
  }


  private static String generatePomContent(String repoName, Model parentModel, List<Model> mavenModels) throws Exception {
    // Load the sample pom.xml from resources
    Model model;
    try (InputStream inputStream = MavenUtils.class.getResourceAsStream(POM)) {
      Objects.requireNonNull(inputStream, "Sample POM file not found in resources.");
      MavenXpp3Reader reader = new MavenXpp3Reader();
      model = reader.read(inputStream);
    }

    // Update groupId, artifactId, version, and dependencies
    model.setGroupId(parentModel.getGroupId());
    model.setArtifactId(repoName + APP);
    model.setVersion(parentModel.getVersion());
    model.setScm(parentModel.getScm());

    // Add or update dependencies
    for (var mavenModel : mavenModels) {
      Dependency dependency = new Dependency();
      dependency.setGroupId(mavenModel.getGroupId());
      dependency.setArtifactId(mavenModel.getArtifactId());
      dependency.setVersion(mavenModel.getVersion());
      dependency.setType(IAR);
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
      if (StringUtils.startsWith(module, "${")) {
        var modulePrefix = module.substring(0, module.indexOf("}") + 1);
        moduleName = modulePrefix + moduleNamePostfix;
        break;
      }
    }
    return moduleName;
  }

  public record AppProject(String pom, String assembly, String deployOptions) {}
  public record MavenModel(Model pom, Set<Model> pomModules) {}
}
