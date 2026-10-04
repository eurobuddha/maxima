package com.eurobuddha.maxima.core.chat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Web links in literal chat text. Never interprets HTML or fetches previews. */
public final class ChatLinks {
    private ChatLinks() {}
    // Consume explicit schemes whole so a rejected URL cannot expose a link inside it.
    private static final Pattern WEB = Pattern.compile("(?i)(?<![\\p{L}\\p{N}_@./:+%\\-])(?:[a-z][a-z0-9+.-]*://[^\\s<>\\\"\\p{Cntrl}]+|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.){1,126}[a-z]{2,63}(?::[0-9]{1,5})?(?:[/?#][^\\s<>\\\"\\p{Cntrl}]*)?)(?![a-z0-9_\\-])");
    public static final class Link {
        public final int start, end;
        public final String url;
        Link(int start, int end, String url) { this.start=start; this.end=end; this.url=url; }
    }
    public static List<Link> find(String text) {
        List<Link> links = new ArrayList<>();
        if (text == null || text.isEmpty()) return links;
        Matcher m = WEB.matcher(text);
        while (m.find()) {
            int end = m.end();
            int[] balance = new int[3];
            for (int i=m.start(); i<end; i++) {
                int open = "([{".indexOf(text.charAt(i)), close = ")]}".indexOf(text.charAt(i));
                if (open>=0) balance[open]++;
                if (close>=0) balance[close]--;
            }
            while (end > m.start()) {
                char last = text.charAt(end-1);
                if (".,!?;:'".indexOf(last)>=0) { end--; continue; }
                int close = ")]}".indexOf(last);
                if (close>=0) {
                    if(balance[close]<0) { balance[close]++; end--; continue; }
                }
                break;
            }
            String raw=text.substring(m.start(),end);
            String url=raw.contains("://") ? raw : "https://"+raw;
            try {
                URI uri=URI.create(url);
                if (uri.getHost()!=null && uri.getHost().length() <= 253 && uri.getRawUserInfo()==null &&
                        ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                    links.add(new Link(m.start(),end,url));
            } catch(IllegalArgumentException ignored) {}
        }
        return links;
    }
}
