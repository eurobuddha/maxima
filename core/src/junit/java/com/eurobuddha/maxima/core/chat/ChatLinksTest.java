package com.eurobuddha.maxima.core.chat;
import org.junit.Test;
import static org.junit.Assert.*;
public class ChatLinksTest {
    @Test public void preservesTextAndPunctuation() {
        String s = "Hello 🙂 (https://example.org/a_(b)). www.example.org/test, done";
        java.util.List<ChatLinks.Link> links=ChatLinks.find(s);
        assertEquals(2,links.size());
        assertEquals("https://example.org/a_(b)",links.get(0).url);
        assertEquals("https://example.org/a_(b)",s.substring(links.get(0).start,links.get(0).end));
        assertEquals("https://www.example.org/test",links.get(1).url);
    }
    @Test public void permitsQueriesPortsAndFragments() {
        assertEquals("http://192.168.1.2:8080/path?a=1&b=2#part",ChatLinks.find("http://192.168.1.2:8080/path?a=1&b=2#part").get(0).url);
    }
    @Test public void rejectsOtherSchemesCredentialsAndMalformedHosts() {
        assertTrue(ChatLinks.find("javascript:alert(1) file:///tmp/x data:text/html,x https://user:pass@example.org https://").isEmpty());
        assertTrue(ChatLinks.find("just plain text and an Mx123 address").isEmpty());
    }
    @Test public void bareDomainsKeepPathsAndDefaultToHttps() {
        String text = "See example.com, (docs.example.co.uk:8443/a_(b)?x=1&y=2#part).";
        java.util.List<ChatLinks.Link> links = ChatLinks.find(text);
        assertEquals(2, links.size());
        assertEquals("https://example.com", links.get(0).url);
        assertEquals("example.com", text.substring(links.get(0).start, links.get(0).end));
        assertEquals("https://docs.example.co.uk:8443/a_(b)?x=1&y=2#part", links.get(1).url);
    }
    @Test public void bareDomainsDoNotLinkInsideEmailsSchemesOrBadHosts() {
        assertTrue(ChatLinks.find("user@example.com mailto:user@example.com ftp://example.com file://example.com javascript:example.com https://user:pass@example.com").isEmpty());
        assertTrue(ChatLinks.find("example.com_foo example.com1 -example.com bad-.example.com 1.23 0.6.131").isEmpty());
        assertTrue(ChatLinks.find(null).isEmpty());
    }
    @Test public void excessiveDomainLabelsRemainPlainText() {
        assertTrue(ChatLinks.find("a.".repeat(10000) + "com").isEmpty());
        assertTrue(ChatLinks.find("a".repeat(64) + ".com").isEmpty());
    }
}
