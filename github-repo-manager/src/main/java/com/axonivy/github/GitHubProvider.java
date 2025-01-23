package com.axonivy.github;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

public class GitHubProvider {

  public static GitHub get() {
    var file = System.getProperty("GITHUB.TOKEN.FILE", "github.token");
    var path = new File(file).toPath();
    try {
      var token = Files.readString(path);
      return new GitHubBuilder()
              .withOAuthToken(token)
              .build();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  public static GitHub getGithubByToken() {
    try {
      return new GitHubBuilder().withOAuthToken(getConfigToken()).build();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }
  public static UsernamePasswordCredentialsProvider createCredentialFor(String actor) {
    return new UsernamePasswordCredentialsProvider(actor, getConfigToken());
  }

  public static String getConfigToken() {
    return System.getProperty("GITHUB.TOKEN");
  }
}
