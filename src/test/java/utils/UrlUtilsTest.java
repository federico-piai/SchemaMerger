package utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UrlUtilsTest {

	@Test
	public void testGetDomain() {
		assertEquals("www.provaT.com", UrlUtils.getDomain("www.provaT.com"));
	}
	
	@Test
	public void testGetDomainWithSpecialChars() {
		assertEquals("www.prova--s--T.com", UrlUtils.getDomain("www.prova--s--T.com"));
	}
	
	@Test
	public void testGetDomainHTTP() {
		assertEquals("www.provaT.com", UrlUtils.getDomain("http://www.provaT.com"));
	}
	
	@Test
	public void testGetDomainHTTPS() {
		assertEquals("www.provaT.com", UrlUtils.getDomain("https://www.provaT.com"));
	}

	@Test
	public void testGetDomainHTTPSingleSlash() {
		assertEquals("www.provaT.com", UrlUtils.getDomain("http:/www.provaT.com"));
	}
	
	@Test
	public void testGetDomainHTTPSSingleSlash() {
		assertEquals("www.provaT.com", UrlUtils.getDomain("https:/www.provaT.com"));
	}
	
	@Test
	public void testGetDomainHTTPTripleSlash() {
		assertEquals("www.provaT.com", UrlUtils.getDomain("http:///www.provaT.com"));
	}
	
	@Test
	public void testGetDomainHTTPSTripleSlash() {
		assertEquals("www.provaT.com", UrlUtils.getDomain("https:///www.provaT.com"));
	}
}
