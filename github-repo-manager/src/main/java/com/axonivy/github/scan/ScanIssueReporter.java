package com.axonivy.github.scan;

import org.kohsuke.github.GHRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScanIssueReporter {

  private final Path path;
  private GHRepository repo;

  public ScanIssueReporter(Path path) {
    this.path = path;
  }

  public void setRepo(GHRepository repo) {
    this.repo = repo;
  }

  public void print(String message) {
    var msg = repo == null ? message : "<b>" + repo.getFullName() + "</b>: " + message;
    msg = msg + "<br />";
    printHtml(msg);
  }

  private void printHtml(String msg) {
    try {
      if (!Files.exists(path)) {
        Files.createFile(path);
      }
      Files.writeString(path, msg, StandardOpenOption.APPEND);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  public void report(String tag, Set<Issue> logIssues, Set<Issue> rnIssues) {
    repo = null;

    var sortedIssues = Stream.concat(logIssues.stream(), rnIssues.stream())
        .distinct()
        .sorted()
        .collect(Collectors.toList());
    print("");
    print("");
    print("<b>Issues Report</b>");
    if (sortedIssues.isEmpty()) {
      print("Found no issues since tag " + tag);
    } else {
      print("Found " + sortedIssues.size() + " issues since tag " + tag);
      print("--------------------------------------------------");
      printHtml("<table>");
      printHtml("<thead><tr><td>Issue</td><td>Log</td><td>Release Notes</td></tr></thead>");
      printHtml("<tbody>");
      for (var issue : sortedIssues) {
        printHtml("<tr>");
        printHtml("<td><a target=\"_blank\" href=\"https://1ivy.atlassian.net/browse/" + issue + "\">" + issue + "</a></td>");
        printContains("Log", logIssues, issue);
        printContains("Release Notes", rnIssues, issue);
        printHtml("</tr>");
      }
      printHtml("</tbody>");
      printHtml("</table>");
    }
  }

  private void printContains(String kind, Set<Issue> logIssues, Issue issue) {
    printHtml("<td>");
    if (logIssues.contains(issue)) {
      printHtml("<span style=\"color: green;\">");
      printHtml(kind + " &#x2713;");
    } else {
      printHtml("<span style=\"color: red;\">");
      printHtml(kind + " &#x2715;");
    }
    printHtml("<span></td>");
  }
}
