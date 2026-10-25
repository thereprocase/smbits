package dev.gridline.files.core;

import java.io.*;
import java.util.*;

/** Paths are provider-owned opaque identifiers. All child names are validated. */
public interface Store extends AutoCloseable {
  record
      Entry(String path, String name, boolean directory, long size, long modified, boolean link) {}
  String id();
  default String namespace() {
    return id();
  }
  String label();
  String root();
  default String displayPath(String path) {
    return path.isEmpty() ? "/" : path.replace('\\', '/');
  }
  List<Entry> list(String directory) throws IOException;
  Entry stat(String path) throws IOException;
  String child(String directory, String name) throws IOException;
  String mkdir(String directory, String name) throws IOException;
  String create(String directory, String name) throws IOException;
  InputStream read(String path) throws IOException;
  OutputStream write(String path) throws IOException;
  /** Must fail if the target exists; never silently replace. */
  String rename(String path, String directory, String name) throws IOException;
  void delete(String path, boolean directory) throws IOException;
  default boolean exists(String directory, String name) throws IOException {
    return child(directory, name) != null;
  }
  default void close() throws Exception {}
  static String name(String name) {
    if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..")
        || name.length() > 255 || name.endsWith(".") || name.endsWith(" ")
        || name.chars().anyMatch(c -> c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0))
      throw new IllegalArgumentException(
          "Use a file name without path separators or reserved characters.");
    String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
    if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
      throw new IllegalArgumentException("Reserved file name.");
    return name;
  }
}
