package com.eurobuddha.maxima.core.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A group, modelled on FreezePeach's (Db.java:52).
 *
 * Members and admins are identity public keys - NOT addresses. A member who
 * changes host is still the same member; that distinction is the whole reason
 * the transport keys on identity.
 */
public final class Group {

    public String id;
    public String name = "Group";

    /**
     * Identity public keys, normalised.
     *
     * These are deliberately NOT public. An earlier version exposed the raw
     * sets, and a caller doing members.remove("0xBob") silently failed because
     * the stored form is normalised - the set is a trap unless every mutation
     * goes through normalisation. Use the accessors.
     */
    private final Set<String> mMembers = new LinkedHashSet<>();
    private final Set<String> mAdmins = new LinkedHashSet<>();
    public long lastActivity;

    /**
     * The most members a group may have (admins included, ourselves included). Every post is
     * one sealed message per member on the sender's device, so this is the number at which a
     * group is still instant to post to and cheap to resend into; a "wall" (post once, read by
     * many) is the design that lifts it, not a bigger number here.
     */
    public static final int MAX_MEMBERS = 12;

    /** Delegates to the one shared normaliser. */
    public static String norm(String zKey) {
        return com.eurobuddha.maxima.core.identity.Keys.norm(zKey);
    }

    public Group(String zId) {
        id = zId;
    }

    public boolean isMember(String zPublicKey) {
        return mMembers.contains(norm(zPublicKey));
    }

    public boolean isAdmin(String zPublicKey) {
        return mAdmins.contains(norm(zPublicKey));
    }

    public void addMember(String zPublicKey) {
        mMembers.add(norm(zPublicKey));
    }

    /** An admin is always also a member. */
    public void addAdmin(String zPublicKey) {
        mAdmins.add(norm(zPublicKey));
        mMembers.add(norm(zPublicKey));
    }

    /** Removes them from fan-out immediately - there is no group key to rotate. */
    public boolean removeMember(String zPublicKey) {
        String k = norm(zPublicKey);
        mAdmins.remove(k);
        return mMembers.remove(k);
    }

    public boolean removeAdmin(String zPublicKey) {
        return mAdmins.remove(norm(zPublicKey));
    }

    /** Read-only. Mutate via the accessors so normalisation cannot be bypassed. */
    public Set<String> members() {
        return java.util.Collections.unmodifiableSet(mMembers);
    }

    public Set<String> admins() {
        return java.util.Collections.unmodifiableSet(mAdmins);
    }

    public void setMembers(java.util.Collection<String> zKeys) {
        mMembers.clear();
        for (String k : zKeys) {
            mMembers.add(norm(k));
        }
    }

    public void setAdmins(java.util.Collection<String> zKeys) {
        mAdmins.clear();
        for (String k : zKeys) {
            mAdmins.add(norm(k));
        }
    }

    public int size() {
        return mMembers.size();
    }

    /** Everyone except us - the fan-out list. */
    public List<String> others(String zMe) {
        List<String> out = new ArrayList<>();
        String me = norm(zMe);
        for (String m : mMembers) {
            if (!m.equals(me)) {
                out.add(m);
            }
        }
        return out;
    }

    public String adminsCsv() {
        return String.join(",", mAdmins);
    }

    public static Set<String> parseCsv(String zCsv) {
        Set<String> out = new LinkedHashSet<>();
        if (zCsv == null) {
            return out;
        }
        for (String s : zCsv.split(",")) {
            if (!s.trim().isEmpty()) {
                out.add(norm(s));
            }
        }
        return out;
    }
}
