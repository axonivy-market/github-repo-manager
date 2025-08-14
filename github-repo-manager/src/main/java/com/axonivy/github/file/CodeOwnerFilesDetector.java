package com.axonivy.github.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;

import java.io.IOException;
import java.util.List;

public class CodeOwnerFilesDetector extends GitHubMissingFilesDetector {
  private static final String CODE_OWNER_FILE_NAME = "CodeOwners.json";
  private static final TypeReference<List<CodeOwner>> CODE_OWNER_TYPE_REFERENCE = new TypeReference<>() {
  };
  private static final String CODE_OWNER_FORMAT = "*  %s";
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private List<CodeOwner> codeOwners;

  public CodeOwnerFilesDetector(GitHubFiles.FileMeta fileMeta, String user) throws IOException {
    super(fileMeta, user);
  }

  @Override
  protected boolean hasSimilarContent(GHContent existingFile) throws IOException {
    // The code owners has a lot of rulesets, and we should not override the existing config
    try (var inputStream = existingFile.read()) {
      return StringUtils.isNoneBlank(new String(inputStream.readAllBytes()));
    }
  }

  @Override
  protected byte[] loadReferenceFileContent(String repoURL) throws IOException {
    if (StringUtils.isBlank(repoURL)) {
      return super.loadReferenceFileContent(repoURL);
    }

    for (var codeOwner : getAllCodeOwners()) {
      if (StringUtils.contains(repoURL, codeOwner.product)) {
        return String.format(CODE_OWNER_FORMAT, codeOwner.owner).getBytes();
      }
    }
    return null;
  }

  private List<CodeOwner> getAllCodeOwners() throws IOException {
    try (var is = CodeOwnerFilesDetector.class.getResourceAsStream(CODE_OWNER_FILE_NAME)) {
      codeOwners = objectMapper.readValue(is, CODE_OWNER_TYPE_REFERENCE);
    }
    return codeOwners;
  }

  record CodeOwner(String product, String owner) {
  }
}
