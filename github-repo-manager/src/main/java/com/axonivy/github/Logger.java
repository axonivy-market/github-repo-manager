package com.axonivy.github;

import java.text.MessageFormat;

public class Logger {

  public void info(String pattern, Object... arguments) {
    System.out.println(MessageFormat.format(pattern, arguments));
  }

  public void error(String pattern, Object... arguments) {
    System.err.println(MessageFormat.format(pattern, arguments));
  }
}