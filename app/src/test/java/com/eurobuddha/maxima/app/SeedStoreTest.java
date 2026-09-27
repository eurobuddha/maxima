package com.eurobuddha.maxima.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.codec.MiniData;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real AES/GCM + identity derivation; only Android storage boundaries are mocked. */
public class SeedStoreTest {
    @Test public void customPhraseSurvivesEncryptedStorageAndEveryLoadPath() throws Exception {
        try (Storage storage = new Storage()) {
            for (String phrase : new String[]{"my NonBip39 phrase!", "  Case  Matters!  ",
                    "abandon ability", "Café\t猫\n123", "a\"b\\c"}) {
                MaximaIdentity expected = MaximaIdentity.fromSeed(new MiniData(
                        MessageDigest.getInstance("SHA3-256").digest(phrase.getBytes(StandardCharsets.UTF_8))));
                assertArrayEquals(org.minima.utils.BIP39.convertStringToSeed(phrase).getBytes(),
                        expected.seed().getBytes());
                SeedStore.ImportResult restored = SeedStore.importPhrase(storage.context, phrase, true);
                assertEquals(expected.publicKeyHex(), restored.identity.publicKeyHex());
                assertEquals(phrase, SeedStore.revealPhrase(storage.context));
                assertTrue(SeedStore.usesAnyPhrase(storage.context));
                assertEquals(expected.publicKeyHex(), SeedStore.loadIdentity(storage.context).publicKeyHex());
                assertEquals(expected.publicKeyHex(), SeedStore.loadOrCreateIdentity(storage.context).publicKeyHex());
                assertEquals(expected.publicKeyHex(), SeedStore.createNewIdentity(storage.context).publicKeyHex());
                assertFalse(storage.values.containsValue(phrase));
                assertFalse(restored.checksumValid);
            }
        }
    }

    @Test public void standardRestoreResetsModeAndOlderStoredPhrasesKeepTheirIdentity() throws Exception {
        try (Storage storage = new Storage()) {
            SeedStore.importPhrase(storage.context, "abandon ability", true);
            String customKey = SeedStore.loadIdentity(storage.context).publicKeyHex();
            SeedStore.importPhrase(storage.context, "aban abil");
            assertFalse(SeedStore.usesAnyPhrase(storage.context));
            assertEquals("ABANDON ABILITY", SeedStore.revealPhrase(storage.context));
            String normalKey = MaximaIdentity.fromPhrase("abandon ability").publicKeyHex();
            assertNotEquals(customKey, normalKey);
            assertEquals(normalKey, SeedStore.loadIdentity(storage.context).publicKeyHex());
            storage.values.remove("any_phrase");
            assertEquals(normalKey, SeedStore.loadIdentity(storage.context).publicKeyHex());
        }
    }

    @Test public void invalidInputCannotReplaceExistingIdentity() throws Exception {
        try (Storage storage = new Storage()) {
            SeedStore.importPhrase(storage.context, "Synthetic Custom!", true);
            String original = SeedStore.loadIdentity(storage.context).publicKeyHex();
            for (String phrase : new String[]{null, "", " \t\n "}) {
                assertThrows(IllegalArgumentException.class,
                        () -> SeedStore.importPhrase(storage.context, phrase, true));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> SeedStore.importPhrase(storage.context, "not-a-dictionary-word"));
            assertEquals(original, SeedStore.loadIdentity(storage.context).publicKeyHex());
        }
    }

    @Test public void storageFailureIsNotReportedAsSuccessfulRestore() throws Exception {
        try (Storage storage = new Storage()) {
            when(storage.editor.commit()).thenReturn(false);
            assertThrows(IllegalStateException.class,
                    () -> SeedStore.importPhrase(storage.context, "Synthetic Custom!", true));
        }
    }

    private static final class Storage implements AutoCloseable {
        final Context context = mock(Context.class);
        final SharedPreferences prefs = mock(SharedPreferences.class);
        final SharedPreferences.Editor editor = mock(SharedPreferences.Editor.class);
        final Map<String, Object> values = new HashMap<>();
        final MockedStatic<KeyStore> keyStores;
        final MockedStatic<Base64> base64;

        Storage() throws Exception {
            when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs);
            when(prefs.getString(anyString(), any())).thenAnswer(i -> values.getOrDefault(i.getArgument(0), i.getArgument(1)));
            when(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> values.getOrDefault(i.getArgument(0), i.getArgument(1)));
            when(prefs.edit()).thenReturn(editor);
            Map<String, Object> pending = new HashMap<>();
            when(editor.putString(anyString(), anyString())).thenAnswer(i -> { pending.put(i.getArgument(0), i.getArgument(1)); return editor; });
            when(editor.putBoolean(anyString(), anyBoolean())).thenAnswer(i -> { pending.put(i.getArgument(0), i.getArgument(1)); return editor; });
            when(editor.commit()).thenAnswer(i -> { values.putAll(pending); pending.clear(); return true; });
            KeyStore ks = mock(KeyStore.class);
            when(ks.containsAlias(anyString())).thenReturn(true);
            when(ks.getKey(anyString(), isNull())).thenReturn(new SecretKeySpec(new byte[32], "AES"));
            keyStores = mockStatic(KeyStore.class);
            keyStores.when(() -> KeyStore.getInstance("AndroidKeyStore")).thenReturn(ks);
            base64 = mockStatic(Base64.class);
            base64.when(() -> Base64.encodeToString(any(byte[].class), anyInt())).thenAnswer(i -> java.util.Base64.getEncoder().encodeToString(i.getArgument(0)));
            base64.when(() -> Base64.decode(anyString(), anyInt())).thenAnswer(i -> java.util.Base64.getDecoder().decode((String) i.getArgument(0)));
        }
        @Override public void close() { base64.close(); keyStores.close(); }
    }
}
