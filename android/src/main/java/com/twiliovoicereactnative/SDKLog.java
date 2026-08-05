package com.twiliovoicereactnative;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Vector;

class SDKLog extends OutputStream {
  private final Vector<Character> logInfoBuffer = new Vector<>();
  // >>> FORK KAR-878 — see ForkLogger.java
  private final ForkLogger forkLogger;
  // <<< FORK
  public SDKLog(Class<?> clazz) {
    // >>> FORK KAR-878 — see ForkLogger.java
    forkLogger = new ForkLogger(clazz);
    // <<< FORK
  }

  public void debug(final String message) {
    // >>> FORK KAR-878 — see ForkLogger.java
    forkLogger.debug(message);
    // <<< FORK
  }

  public void log(final String message) {
    // >>> FORK KAR-878 — see ForkLogger.java
    forkLogger.info(message);
    // <<< FORK
  }

  public void warning(final String message) {
    // >>> FORK KAR-878 — see ForkLogger.java
    forkLogger.warning(message);
    // <<< FORK
  }

  public void error(final String message) {
    // >>> FORK KAR-878 — see ForkLogger.java
    forkLogger.error(message);
    // <<< FORK
  }

  public void warning(final Exception e, final String message) {
    // >>> FORK KAR-878 — see ForkLogger.java
    forkLogger.warning(e, message);
    // <<< FORK
  }

  // >>> FORK KAR-878 — see ForkLogger.java
  public void error(final Exception e, final String message) {
    forkLogger.error(e, message);
  }
  // <<< FORK

  @Override
  public synchronized void write(int i) throws IOException {
    logInfoBuffer.add((char)i);
  }

  @Override
  public synchronized void write(byte[] b) throws IOException {
    for (char c: (new String(b, Charset.defaultCharset()).toCharArray())) {
      logInfoBuffer.add(c);
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    for (char c: (new String(b, off, len, Charset.defaultCharset()).toCharArray())) {
      logInfoBuffer.add(c);
    }
  }

  @Override
  public synchronized void flush() throws IOException {
    char [] output = new char[logInfoBuffer.size()];
    for (int i = 0; i < logInfoBuffer.size(); ++i) {
      output[i] = logInfoBuffer.get(i);
    }
    logInfoBuffer.clear();
    // >>> FORK KAR-878 — see ForkLogger.java
    forkLogger.warning(String.valueOf(output));
    // <<< FORK
  }
}
