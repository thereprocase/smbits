package dev.gridline.files.core;

import java.util.*;

public record Unc(String host, String share, String path) {
  public static Unc parse(String value) {
    String s = value.trim();
    if (s.regionMatches(true, 0, "smb://", 0, 6))
      s = "\\\\" + s.substring(6).replace('/', '\\');
    if (!s.startsWith("\\\\"))
      throw new IllegalArgumentException("Enter \\\\server\\share or smb://server/share.");
    String[] bits = s.substring(2).split("\\\\", -1);
    if (bits.length < 2 || bits[0].trim().isEmpty() || bits[0].contains("@")
        || bits[0].matches(".*[\\s/?#].*"))
      throw new IllegalArgumentException(
          "A server and share are required. Enter credentials separately.");
    // Host names and IPv4/IPv6 literals only; ports and URI credentials are not accepted.
    if (!bits[0].matches("[a-zA-Z0-9._:\\[\\]-]+"))
      throw new IllegalArgumentException("Invalid server name.");
    Store.name(bits[1]);
    List<String> parts = new ArrayList<>();
    for (int i = 2; i < bits.length; i++)
      if (!bits[i].isEmpty())
        parts.add(Store.name(bits[i]));
    return new Unc(bits[0], bits[1], String.join("\\", parts));
  }
  public String address() {
    return "\\\\" + host + "\\" + share;
  }
}
