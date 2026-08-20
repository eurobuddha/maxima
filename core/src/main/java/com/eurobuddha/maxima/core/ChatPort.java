package com.eurobuddha.maxima.core;

import com.eurobuddha.maxima.core.contacts.Contact;

import java.util.List;

/**
 * The surface the chat brain (and the chat/contacts UI) needs from a routing
 * engine - and nothing more.
 *
 * Two engines implement it:
 *  - {@link MaximaNode}: the original reimplementation (fleet routing).
 *  - the maxjar adapter: classic Maxima extracted whole, chain-free - the
 *    settled direction. ChatEngine rides either without knowing which, exactly
 *    like MaxSolo rides classic transport.
 */
public interface ChatPort {

	/** My identity public key, 0x hex - the "from" of everything I sign. */
	String publicKeyHex();

	/** My display name. */
	String name();

	/** The stored contact for this public key, or null. */
	Contact contact(String zPublicKey);

	/** Every stored contact. */
	List<Contact> contacts();

	/** Send to a contact, trying every route the engine knows. */
	MaximaSender.Result sendToContact(Contact zContact, String zApplication, byte[] zData)
			throws Exception;

	/** Send a contact-ctrl introduction (or update) to an address. */
	void introduce(String zPeerAddress, boolean zIntro) throws Exception;

	/** Remove a contact (and tell them, classic-style). */
	boolean removeContact(String zPublicKey);

	/** The addresses peers can reach me at right now. */
	List<String> myAddresses();

	/** A diagnostic line for the in-app event log. */
	void log(String zLine);
}
