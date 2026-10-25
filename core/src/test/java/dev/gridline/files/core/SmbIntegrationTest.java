package dev.gridline.files.core;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;

/** Runs only against the explicitly supplied isolated loopback test server. */
public class SmbIntegrationTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  @Test
  public void encryptedRoundTripBrowseRenameMoveAndDelete() throws Exception {
    Assume.assumeTrue("Run scripts/test-smb.sh for an isolated Samba server",
        Boolean.getBoolean("smb.integration"));
    Assume.assumeFalse(Boolean.getBoolean("smb.legacy"));
    try (SmbStore smb = new SmbStore(Unc.parse("\\\\127.0.0.1\\files"), "smbtest",
             "test-only-password".toCharArray(), "", SmbStore.Security.ENCRYPTED, 1445)) {
      assertTrue(smb.negotiated().contains("SMB 3"));
      Thread.sleep(
          22_000); // Regression: a healthy session must survive a user pausing between actions.
      // Simulate a background/network disconnect between user actions.
      java.lang.reflect.Field transport = SmbStore.class.getDeclaredField("connection");
      transport.setAccessible(true);
      ((com.hierynomus.smbj.connection.Connection) transport.get(smb)).close(true);
      smb.list("");
      assertTrue(smb.negotiated().contains("SMB 3"));
      String folder = smb.mkdir("", "run-" + UUID.randomUUID());
      TransferTest.Disk local = new TransferTest.Disk(temp.newFolder("upload").toPath());
      byte[] data = new byte[1024 * 1024 + 123];
      new Random(17).nextBytes(data);
      for (int i = 0; i < 12; i++) Files.write(local.root.resolve("item-" + i + ".bin"), data);
      Transfer.Job upload = new Transfer.Job(
          local, local.list(local.root()), smb, folder, false, Transfer.Conflict.STOP, 8, 0);
      new Transfer().run(upload);
      assertEquals(upload.errors.toString(), Transfer.State.DONE, upload.state);
      assertEquals(12, smb.list(folder).size());
      String renamed = smb.rename(smb.child(folder, "item-0.bin"), folder, "renamed.bin");
      assertNotNull(smb.child(folder, "renamed.bin"));
      TransferTest.Disk download = new TransferTest.Disk(temp.newFolder("download").toPath());
      Transfer.Job down = new Transfer.Job(
          smb, smb.list(folder), download, download.root(), true, Transfer.Conflict.STOP, 8, 0);
      new Transfer().run(down);
      assertEquals(down.errors.toString(), Transfer.State.DONE, down.state);
      assertEquals(12, down.completed.get());
      assertArrayEquals(data, Files.readAllBytes(download.root.resolve("renamed.bin")));
      assertTrue(smb.list(folder).isEmpty());
      smb.delete(folder, true);
    }
  }
  @Test
  public void incorrectPasswordIsRejected() throws Exception {
    Assume.assumeTrue(Boolean.getBoolean("smb.integration"));
    Assume.assumeFalse(Boolean.getBoolean("smb.legacy"));
    assertThrows(IOException.class,
        ()
            -> new SmbStore(Unc.parse("\\\\127.0.0.1\\files"), "smbtest", "wrong".toCharArray(), "",
                SmbStore.Security.ENCRYPTED, 1445));
  }
  @Test
  public void encryptedPolicyRefusesSmb2WithoutDowngrade() throws Exception {
    Assume.assumeTrue(Boolean.getBoolean("smb.legacy"));
    assertThrows(IOException.class,
        ()
            -> new SmbStore(Unc.parse("\\\\127.0.0.1\\files"), "smbtest",
                "test-only-password".toCharArray(), "", SmbStore.Security.ENCRYPTED, 1445));
  }
  @Test
  public void explicitSignedCompatibilityCanUseSmb2() throws Exception {
    Assume.assumeTrue(Boolean.getBoolean("smb.legacy"));
    try (SmbStore smb = new SmbStore(Unc.parse("\\\\127.0.0.1\\files"), "smbtest",
             "test-only-password".toCharArray(), "", SmbStore.Security.SIGNED, 1445)) {
      assertTrue(smb.negotiated().contains("SMB 2"));
      String path = smb.create("", "signed-test.bin");
      try (OutputStream out = smb.write(path)) {
        out.write(new byte[] {1, 2, 3});
      }
      try (InputStream in = smb.read(path)) {
        assertArrayEquals(new byte[] {1, 2, 3}, in.readAllBytes());
      }
      smb.delete(path, false);
    }
  }
}
