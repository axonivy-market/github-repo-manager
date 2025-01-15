package com.axonivy.github.file;

import com.axonivy.github.Logger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHTeam;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class CodeOwnerFilesDetector extends GitHubMissingFilesDetector {
  private static final Logger LOG = new Logger();
  private static final String CODE_OWNER_FILE_NAME = "CodeOwners.json";
  private static final TypeReference<List<CodeOwner>> CODE_OWNER_TYPE_REF = new TypeReference<>() { };
  private static final String CODE_OWNER_FORMAT = "*  %s";
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private List<CodeOwner> codeOwners;

  public CodeOwnerFilesDetector(GitHubFiles.FileMeta fileMeta, String user) throws IOException {
    super(fileMeta, user);
  }

  @Override
  protected void missingFile(GHRepository repo) throws IOException {
    super.missingFile(repo);
    manageAccessForRepo(repo);
  }

  private void manageAccessForRepo(GHRepository repo) throws IOException {
    for (var codeOwner : getAllCodeOwners()) {
      if (filterCoderOwnerByRef(repo.getUrl().toString(), codeOwner)) {
        LOG.info("Found {0} team has no access to repo {1}", codeOwner.owner, repo.getName());
        Set<GHTeam> existingTeams = repo.getTeams();
        if (existingTeams.stream().noneMatch(team -> team.getName().equals(codeOwner.owner))) {
          GHTeam foundTeam = findTeamByName(codeOwner.owner);
          if (foundTeam != null) {
          }
          existingTeams.add(findTeamByName(codeOwner.owner));
          LOG.info("Added {0} team to repo {1}", codeOwner.owner, repo.getName());
        }
      }
    }
  }

  private GHTeam findTeamByName(String teamName) throws IOException {
    for (var org : organizations) {
      var team = org.getTeamByName(teamName);
      if (team != null) {
        LOG.info("Found {0} team by name {1}", team.getId(), teamName);
        return team;
      }
    }
    return null;
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
      if (filterCoderOwnerByRef(repoURL, codeOwner)) {
        return String.format(CODE_OWNER_FORMAT, codeOwner.owner).getBytes();
      }
    }
    return null;
  }

  private static boolean filterCoderOwnerByRef(String repoURL, CodeOwner codeOwner) {
    return StringUtils.contains(repoURL, codeOwner.product);
  }

  private List<CodeOwner> getAllCodeOwners() throws IOException {
    if (ObjectUtils.isEmpty(codeOwners)) {
      try (var is = CodeOwnerFilesDetector.class.getResourceAsStream(CODE_OWNER_FILE_NAME)) {
        codeOwners = objectMapper.readValue(is, CODE_OWNER_TYPE_REF);
      }
    }
    return codeOwners;
  }

  record CodeOwner(String product, String owner) {
  }
}
