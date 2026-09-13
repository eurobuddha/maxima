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
}
