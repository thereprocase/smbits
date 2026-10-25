package dev.gridline.files;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials are never saved without opt-in; the AES key remains in Android Keystore. */
public final class Vault {
  private static final String ALIAS = "smbfiles.credentials.v1";
  private final Context context;
  public Vault(Context context) {
    this.context = context;
  }
  private synchronized SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(ALIAS)) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(new KeyGenParameterSpec
              .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .setKeySize(256)
              .setUnlockedDeviceRequired(true)
              .build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(ALIAS, null);
  }
  public void put(String id, String secret) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
    byte[] plain = secret.getBytes(StandardCharsets.UTF_8);
    try {
      String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
          + Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP);
      context.getSharedPreferences("vault", 0).edit().putString(id, value).apply();
    } finally {
      java.util.Arrays.fill(plain, (byte) 0);
    }
  }
  public String get(String id) throws Exception {
    String value = context.getSharedPreferences("vault", 0).getString(id, null);
    if (value == null)
      return "";
    String[] parts = value.split(":");
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
    byte[] plain = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
    try {
      return new String(plain, StandardCharsets.UTF_8);
    } finally {
      java.util.Arrays.fill(plain, (byte) 0);
    }
  }
  public void remove(String id) {
    context.getSharedPreferences("vault", 0).edit().remove(id).apply();
  }
}
