package com.eurobuddha.maxima.app.chat;

import com.eurobuddha.maxima.core.ChatPort;
import com.eurobuddha.maxima.core.chat.ChatEngine;
import com.eurobuddha.maxima.core.chat.Group;
import com.eurobuddha.maxima.core.contacts.Contact;
import com.eurobuddha.maxima.core.identity.Keys;

/**
 * Turning keys into something a human recognises.
 *
 * Every conversation is identified by a 0x-hex key, which is correct and
 * unreadable. One place does the lookup so the list, the thread title and the
 * notification can never disagree about who someone is.
 */
public final class Names {

    private Names() {
    }

    /** A conversation key is either a group id or a contact's identity key. */
    public static String of(com.eurobuddha.maxima.core.ChatPort zNode, ChatEngine zChat, String zConversation) {
        if (zConversation == null || zConversation.isEmpty()) {
            return "(unknown)";
        }
        if (zChat != null) {
            Group g = zChat.group(zConversation);
            if (g != null) {
                return g.name + "  (" + g.size() + ")";
            }
        }
        return contact(zNode, zConversation);
    }

    /** A single identity, named if we know them, abbreviated if we do not. */
    public static String contact(com.eurobuddha.maxima.core.ChatPort zNode, String zPublicKey) {
        if (zPublicKey == null || zPublicKey.isEmpty()) {
            return "(unknown)";
        }
        if (zNode != null) {
            Contact c = zNode.contact(zPublicKey);
            if (c != null && c.name != null && !c.name.isEmpty()) {
                return c.name;
            }
        }
        return shorten(zPublicKey);
    }

    /**
     * Enough of the key to tell two people apart without pretending it is a
     * name - shown only when we have no better answer.
     */
    public static String shorten(String zKey) {
        String k = Keys.norm(zKey);
        return k.length() > 14 ? k.substring(0, 10) + "…" + k.substring(k.length() - 4) : k;
    }
}
