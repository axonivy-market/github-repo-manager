package com.axonivy.github.file;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

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
  protected byte[] loadReferenceFileContent(String repoURL) throws IOException {
    if (StringUtils.isBlank(repoURL)) {
      return super.loadReferenceFileContent(repoURL);
    }

    for (var codeOwner : getAllCodeOwners()) {
      if (StringUtils.contains(repoURL, codeOwner.product)) {
        StringBuilder ownerContent = new StringBuilder();
        for (String owner : codeOwner.owner) {
          ownerContent.append(String.format(CODE_OWNER_FORMAT, owner)).append("\n");
        }
        return ownerContent.toString().getBytes();
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

  record CodeOwner(String product, List<String> owner) {
  }
}
