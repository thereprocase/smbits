package dev.gridline.files.core;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.*;
import com.hierynomus.mssmb2.*;
import com.hierynomus.smbj.*;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class SmbStore implements Store {
  public enum Security { ENCRYPTED, SIGNED }
  private final SMBClient client;
  private Connection connection;
  private Session session;
  private DiskShare share;
  private final Unc unc;
  private final int port;
  private final String username, domain;
  private final char[] sessionPassword;
  private boolean closed;
  private final String connectionId = UUID.randomUUID().toString();
  public final Security security;
  public SmbStore(Unc unc, String username, char[] password, String domain, Security security)
      throws IOException {
    this(unc, username, password, domain, security, 445);
  }
  public SmbStore(Unc unc, String username, char[] password, String domain, Security security,
      int port) throws IOException {
    this.unc = unc;
    this.security = security;
    this.port = port;
    if (username.trim().isEmpty()) {
      Arrays.fill(password, '\0');
      throw new IOException("Authenticated user required");
    }
    this.username = username;
    this.domain = domain;
    this.sessionPassword = password.clone();
    SmbConfig.Builder config = SmbConfig.builder()
                                   .withSigningRequired(true)
                                   .withEncryptData(security == Security.ENCRYPTED)
                                   // A socket read deadline also kills healthy idle SMB sessions.
                                   // Bound requests, not idle time.
                                   .withDfsEnabled(false)
                                   .withTimeout(15, TimeUnit.SECONDS)
                                   .withSoTimeout(0, TimeUnit.SECONDS)
                                   .withBufferSize(256 * 1024);
    config.withDialects(security == Security.ENCRYPTED
            ? new SMB2Dialect[] {SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0}
            : new SMB2Dialect[] {SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0,
                  SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2});
    client = new SMBClient(config.build());
    try {
      ensureConnected();
    } catch (Exception e) {
      try {
        close();
      } catch (Exception ignored) {
      }
      throw new IOException("SMB connection failed: " + Transfer.safeMessage(e), e);
    } finally {
      Arrays.fill(password, '\0');
    }
  }
  /** Recover before an operation; never replay an ambiguous write or rename. */
  private synchronized void ensureConnected() throws IOException {
    if (closed)
      throw new IOException("This connection was disconnected. Open it again from Connections.");
    if (share != null && share.isConnected() && connection != null && connection.isConnected())
      return;
    if (connection != null)
      try {
        connection.close(true);
      } catch (Exception ignored) {
      }
    char[] authenticationPassword = sessionPassword.clone();
    try {
      connection = client.connect(unc.host(), port);
      session = connection.authenticate(
          new AuthenticationContext(username, authenticationPassword, domain));
      if (session.isGuest() || session.isAnonymous())
        throw new IOException("Guest or anonymous sessions are refused");
      share = (DiskShare) session.connectShare(unc.share());
    } catch (Exception failure) {
      if (connection != null)
        try {
          connection.close(true);
        } catch (Exception ignored) {
        }
      throw new IOException("SMB connection failed: " + Transfer.safeMessage(failure), failure);
    } finally {
      Arrays.fill(authenticationPassword, '\0');
    }
  }
  public String negotiated() {
    return connection.getNegotiatedProtocol().getDialect().name().replace('_', ' ')
        + (security == Security.ENCRYPTED ? " · encryption required" : " · signing required");
  }
  public String id() {
    return "smb-session:" + connectionId;
  }
  public String tuningNamespace() {
    return namespace() + "|" + security.name();
  }
  public String namespace() {
    return unc.address().toLowerCase(Locale.ROOT) + ":" + port;
  }
  public String label() {
    return unc.address();
  }
  public String root() {
    return "";
  }
  public String initialPath() {
    return unc.path();
  }
  private String join(String parent, String name) {
    return parent.isEmpty() ? Store.name(name) : parent + "\\" + Store.name(name);
  }
  private boolean bit(long value, FileAttributes attr) {
    return (value & attr.getValue()) != 0;
  }
  private void guard(String path) throws IOException {
    ensureConnected();
    String current = "";
    if (path.isEmpty())
      return;
    for (String part : path.split("\\\\")) {
      current = join(current, part);
      if (bit(share.getFileInformation(current).getBasicInformation().getFileAttributes(),
              FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT))
        throw new IOException("Reparse-point traversal is disabled");
    }
  }
  public List<Entry> list(String directory) throws IOException {
    guard(directory);
    List<Entry> result = new ArrayList<>();
    for (FileIdBothDirectoryInformation f : share.list(directory)) {
      if (f.getFileName().equals(".") || f.getFileName().equals(".."))
        continue;
      result.add(new Entry(join(directory, f.getFileName()), f.getFileName(),
          bit(f.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_DIRECTORY), f.getEndOfFile(),
          f.getLastWriteTime().toEpochMillis(),
          bit(f.getFileAttributes(), FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT)));
    }
    return result;
  }
  public Entry stat(String path) throws IOException {
    guard(path);
    FileAllInformation f = share.getFileInformation(path);
    long attr = f.getBasicInformation().getFileAttributes();
    return new Entry(path, path.substring(path.lastIndexOf('\\') + 1),
        bit(attr, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
        f.getStandardInformation().getEndOfFile(),
        f.getBasicInformation().getLastWriteTime().toEpochMillis(),
        bit(attr, FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT));
  }
  public String child(String directory, String name) throws IOException {
    guard(directory);
    String path = join(directory, name);
    return share.fileExists(path) || share.folderExists(path) ? path : null;
  }
  public String mkdir(String directory, String name) throws IOException {
    guard(directory);
    String path = join(directory, name);
    share.mkdir(path);
    return path;
  }
  public String create(String directory, String name) throws IOException {
    guard(directory);
    String path = join(directory, name);
    try (com.hierynomus.smbj.share.File ignored =
             share.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                 SMB2CreateDisposition.FILE_CREATE,
                 EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
    }
    return path;
  }
  public InputStream read(String path) throws IOException {
    guard(path);
    com.hierynomus.smbj.share.File file = share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ),
        null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    return new FilterInputStream(file.getInputStream()) {
      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          file.close();
        }
      }
    };
  }
  public OutputStream write(String path) throws IOException {
    guard(path);
    com.hierynomus.smbj.share.File file = share.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE),
        null, EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ), SMB2CreateDisposition.FILE_OPEN,
        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    return new FilterOutputStream(file.getOutputStream()) {
      @Override
      public void write(byte[] b, int o, int n) throws IOException {
        out.write(b, o, n);
      }
      @Override
      public void flush() throws IOException {
        super.flush();
        file.flush();
      }
      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          file.close();
        }
      }
    };
  }
  public String rename(String path, String directory, String name) throws IOException {
    guard(path);
    guard(directory);
    String target = join(directory, name);
    Entry entry = stat(path);
    if (entry.directory()) {
      try (Directory f =
               share.openDirectory(path, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                   null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
        f.rename(target, false);
      }
    } else {
      try (com.hierynomus.smbj.share.File f =
               share.openFile(path, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null,
                   SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) {
        f.rename(target, false);
      }
    }
    return target;
  }
  public void delete(String path, boolean directory) throws IOException {
    if (path.isEmpty())
      throw new IOException("Cannot delete a share root");
    guard(path);
    if (directory)
      share.rmdir(path, false);
    else
      share.rm(path);
  }
  public synchronized void close() throws IOException {
    closed = true;
    Arrays.fill(sessionPassword, '\0');
    try {
      if (share != null)
        share.close();
    } finally {
      try {
        if (session != null)
          session.close();
      } finally {
        try {
          if (connection != null)
            connection.close();
        } finally {
          client.close();
        }
      }
    }
  }
}
