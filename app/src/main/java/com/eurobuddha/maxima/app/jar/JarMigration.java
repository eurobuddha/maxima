package com.eurobuddha.maxima.app.jar;

import android.content.Context;
import android.content.SharedPreferences;

import com.eurobuddha.maxima.app.EventLog;
import com.eurobuddha.maxima.app.SeedStore;
import com.eurobuddha.maxima.core.MaximaNode;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.MaximaIdentity;
import com.eurobuddha.maxima.core.store.FileStore;

import org.minima.database.MinimaDB;
import org.minima.database.maxima.MaximaContact;
import org.minima.database.maxima.MaximaDB;
import org.minima.objects.base.MiniData;

import java.io.File;
import java.util.Map;

/**
 * One-time migration of the OLD engine's world into the classic engine, so
 * flipping to the jar keeps WHO YOU ARE and WHO YOU KNOW.
 *
 * Identity: the old RSA identity keys are written into classic's UserDB under
 * classic's own key names BEFORE the MaximaManager first initialises - so the
 * jar boots as the long-standing identity instead of minting a new one. Every
 * peer's stored contact record for us keeps matching; from their side we
 * merely changed address, which classic's refresh heals automatically.
 *
 * Contacts: the old engine's persisted contact records (FileStore "contacts",
 * the same JSON MaximaNode wrote) are replayed into classic's H2 MaximaDB.
 * Chat history needs nothing: threads are keyed by peer identity keys, which
 * are unchanged - old conversations reattach to the migrated contacts.
 *
 * Capabilities: peers the old engine had PROVEN Parlons-capable are seeded
 * into the jar's learned-capability set, so receipts and chat_v1 continue
 * without waiting for a fresh probe round-trip.
 *
 * A pre-migration jar identity (a phone that ran the experimental toggle
 * before this existed) is ARCHIVED, never deleted - the maxjar data dir is
 * renamed aside so nothing is ever lost.
 */
public final class JarMigration {

	private static final String PREFS = "jar_engine";
	private static final String PREF_DONE = "migrated_v1";

	/** Classic's own UserDB key names (MaximaManager.java:90-91). */
	private static final String CLASSIC_PUBKEY = "maxima_publickey";
	private static final String CLASSIC_PRIVKEY = "maxima_privatekey";

	private JarMigration() {
	}

	public static boolean needed(Context zCtx) {
		return !zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				.getBoolean(PREF_DONE, false);
	}

	public static void markDone(Context zCtx) {
		zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				.edit().putBoolean(PREF_DONE, true)
				.putLong("migrated_at", System.currentTimeMillis()).apply();
	}

	/** True within 24h of migration - the window where both ends of a pair may
	 *  have flipped and every stored address needs the forced MLS heal. The
	 *  heal timer dies with the process, so each boot in the window re-arms it. */
	public static boolean inHealWindow(Context zCtx) {
		long at = zCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				.getLong("migrated_at", 0);
		return at > 0 && System.currentTimeMillis() - at < 24L * 60 * 60 * 1000;
	}

	/** Move any pre-migration jar world aside (archived, not deleted). */
	/**
	 * The reverse of {@link #seedContacts}: write the jar's CURRENT contact
	 * book into the built-in engine's store, so flipping engines never loses
	 * a contact added (or re-added) while classic was running. Merge by
	 * public key - the jar's record wins because its address is fresher.
	 *
	 * @return how many contacts were written across
	 */
	public static int exportContacts(Context zCtx, JarEngine zJar) {
		int n = 0;
		try {
			FileStore store = new FileStore(new File(zCtx.getFilesDir(), "node"));
			for (Contact c : zJar.contacts()) {
				if (c.publicKey == null || c.publicKey.isEmpty()) {
					continue;
				}
				String key = com.eurobuddha.maxima.core.identity.Keys.norm(c.publicKey);
				// The jar has no notion of the built-in engine's myAddress
				// (which of MY addresses this peer was told) - keep the old
				// record's value instead of blanking it.
				String prev = store.get("contacts", key);
				if (prev != null && (c.myAddress == null || c.myAddress.isEmpty())) {
					try {
						Contact old = MaximaNode.contactFromJson(prev);
						if (old != null) {
							c.myAddress = old.myAddress;
						}
					} catch (Exception ignored) {
					}
				}
				store.put("contacts", key, MaximaNode.contactToJson(c));
				n++;
			}
		} catch (Exception e) {
			EventLog.add("engine sync: export failed: " + e);
		}
		return n;
	}

	public static void archiveOldJarData(Context zCtx) {
		File data = new File(zCtx.getFilesDir(), "maxjar");
		if (data.exists()) {
			File aside = new File(zCtx.getFilesDir(),
					"maxjar.pre-migration-" + System.currentTimeMillis());
			if (data.renameTo(aside)) {
				EventLog.add("migration: previous jar data archived to " + aside.getName());
			}
		}
	}

	/**
	 * Write the old engine's identity keys into classic's UserDB. MUST run
	 * after MinimaDB.init and BEFORE the MaximaManager first initialises
	 * (MAXIMA_INIT only creates keys when none exist).
	 */
	public static void seedIdentity(Context zCtx) {
		try {
			MaximaIdentity id = SeedStore.loadOrCreateIdentity(zCtx);
			org.minima.database.userprefs.UserDB udb = MinimaDB.getDB().getUserDB();
			udb.setData(CLASSIC_PUBKEY,
					new MiniData(id.keyPair().getPublic().getEncoded()));
			udb.setData(CLASSIC_PRIVKEY,
					new MiniData(id.keyPair().getPrivate().getEncoded()));
			MinimaDB.getDB().saveUserDB();
			EventLog.add("migration: identity carried over (" + id.publicKeyHex() + ")");
		} catch (Exception e) {
			// MUST propagate: a swallowed failure lets classic mint a NEW
			// keypair and boot as a different person forever (markDone would
			// then bar any retry). Boot catches this and does not markDone.
			EventLog.add("migration: identity seed FAILED: " + e);
			throw new RuntimeException("identity seed failed", e);
		}
	}

	/**
	 * Replay the old engine's contact book into classic's MaximaDB and seed
	 * the learned-capability set. Run after the JarEngine is up.
	 *
	 * @return how many contacts were migrated
	 */
	public static int seedContacts(Context zCtx, JarEngine zJar) {
		int n = 0;
		try {
			FileStore store = new FileStore(new File(zCtx.getFilesDir(), "node"));
			MaximaDB maxdb = MinimaDB.getDB().getMaximaDB();
			for (Map.Entry<String, String> e : store.all("contacts").entrySet()) {
				try {
					Contact c = MaximaNode.contactFromJson(e.getValue());
					if (c == null || c.publicKey == null || c.publicKey.isEmpty()) {
						continue;
					}
					String primary = c.primaryAddress();
					if (primary == null || primary.isEmpty()) {
						EventLog.add("migration: skipped " + c.name + " (no address)");
						continue;
					}
					if (maxdb.loadContactFromPublicKey(c.publicKey) != null) {
						continue;   // already known to the jar
					}
					MaximaContact mc = new MaximaContact(c.publicKey);
					mc.setName(c.name == null || c.name.isEmpty() ? "noname" : c.name);
					mc.setIcon(c.icon == null || c.icon.isEmpty() ? "0x00" : c.icon);
					mc.setCurrentAddress(primary);
					mc.setMinimaAddress(c.minimaAddress == null ? "Mx00" : c.minimaAddress);
					mc.setMLS(c.mls == null ? "" : c.mls);
					mc.setLastSeen(c.lastSeen > 0 ? c.lastSeen : System.currentTimeMillis());
					maxdb.newContact(mc);
					// A peer the old engine PROVED capable stays capable - no
					// re-probe round-trip, receipts keep flowing.
					if (!c.isClassic()) {
						zJar.noteCapable(c.publicKey);
					}
					n++;
				} catch (Exception one) {
					EventLog.add("migration: bad contact record skipped: " + one);
				}
			}
		} catch (Exception e) {
			EventLog.add("migration: contact replay FAILED: " + e);
		}
		if (n > 0) {
			EventLog.add("migration: " + n + " contact(s) carried into the classic book");
		}
		return n;
	}
}
