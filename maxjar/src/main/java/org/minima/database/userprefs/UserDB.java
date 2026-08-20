package org.minima.database.userprefs;

import java.util.ArrayList;

import org.minima.utils.JsonDB;
import org.minima.utils.MiniUtil;

/**
 * maxjar CUT-DOWN of classic UserDB: the key/value store Maxima keeps its own
 * keys and settings in. Same keys, same defaults as classic (UserDB.java:95-190)
 * so a JSON file written by either is readable by the other. Everything else in
 * the 336-line original is node preferences and stays behind.
 */
public class UserDB extends JsonDB {

	public void setMaximaName(String zName) {
		setString("maximaname", zName);
	}

	public String getMaximaName() {
		return getString("maximaname", "noname");
	}

	public void setMaximaIcon(String zIcon) {
		setString("maximaicon", zIcon);
	}

	public String getMaximaIcon() {
		return getString("maximaicon", "0x00");
	}

	public boolean getMaximaAllowContacts() {
		return getBoolean("maxima_allowallcontacts", true);
	}

	public void setMaximaAllowContacts(boolean zAllowContacts) {
		setBoolean("maxima_allowallcontacts", zAllowContacts);
	}

	public ArrayList<String> getMaximaPermanent() {
		return MiniUtil.convertJSONArray(getJSONArray("maxima_permanent"));
	}

	public void setMaximaPermanent(ArrayList<String> zPermanentList) {
		setJSONArray("maxima_permanent", MiniUtil.convertArrayList(zPermanentList));
	}
}
