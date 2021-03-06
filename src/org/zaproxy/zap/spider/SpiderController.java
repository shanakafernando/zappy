/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.spider;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import net.htmlparser.jericho.Config;

import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.log4j.Logger;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.parosproxy.paros.network.HttpStatusCode;
import org.zaproxy.zap.spider.filters.FetchFilter;
import org.zaproxy.zap.spider.filters.FetchFilter.FetchStatus;
import org.zaproxy.zap.spider.filters.ParseFilter;
import org.zaproxy.zap.spider.parser.SpiderHtmlFormParser;
import org.zaproxy.zap.spider.parser.SpiderHtmlParser;
import org.zaproxy.zap.spider.parser.SpiderODataAtomParser;
import org.zaproxy.zap.spider.parser.SpiderParser;
import org.zaproxy.zap.spider.parser.SpiderParserListener;
import org.zaproxy.zap.spider.parser.SpiderRedirectParser;
import org.zaproxy.zap.spider.parser.SpiderRobotstxtParser;
import org.zaproxy.zap.spider.parser.SpiderTextParser;

/**
 * The SpiderController is used to manage the crawling process and interacts directly with the
 * Spider Task threads.
 */
public class SpiderController implements SpiderParserListener {

	/** The fetch filters used by the spider to filter the resources which are fetched. */
	private LinkedList<FetchFilter> fetchFilters;

	/**
	 * The parse filters used by the spider to filter the resources which were fetched, but should
	 * not be parsed.
	 */
	private LinkedList<ParseFilter> parseFilters;

	/** The parsers for HTML files. */
	private List<SpiderParser> htmlParsers;

	/** The text parsers. Initialized dynamically, only if needed. */
	private List<SpiderParser> txtParsers;

	/** The parsers for xml files (i.e. OData Atom content) */
	private List<SpiderParser> xmlParsers;

	/** The spider. */
	private Spider spider;

	/** The resources visited using GET method. */
	private Set<String> visitedGet;

	/** The resources visited using POST method. */
	private Set<String> visitedPost;

	/** The Constant log. */
	private static final Logger log = Logger.getLogger(SpiderController.class);

	/**
	 * Instantiates a new spider controller.
	 * 
	 * @param spider the spider
	 */
	protected SpiderController(Spider spider) {
		super();
		this.spider = spider;
		this.fetchFilters = new LinkedList<>();
		this.parseFilters = new LinkedList<>();
		this.visitedGet = new HashSet<>();
		this.visitedPost = new HashSet<>();

		// Prepare the parsers for HTML
		this.htmlParsers = new LinkedList<>();
		// Simple HTML parser
		SpiderParser parser = new SpiderHtmlParser(spider.getSpiderParam());
		parser.addSpiderParserListener(this);
		this.htmlParsers.add(parser);
		Config.CurrentCompatibilityMode.setFormFieldNameCaseInsensitive(false);

		// HTML Form parser
		parser = new SpiderHtmlFormParser(spider.getSpiderParam());
		parser.addSpiderParserListener(this);
		this.htmlParsers.add(parser);

		// Prepare the parsers for simple non-HTML files
		this.txtParsers = new LinkedList<>();
		parser = new SpiderTextParser();
		parser.addSpiderParserListener(this);
		this.txtParsers.add(parser);

		// Prepare the parsers for OData ATOM files
		this.xmlParsers = new LinkedList<>();
		parser = new SpiderODataAtomParser();
		parser.addSpiderParserListener(this);
		this.xmlParsers.add(parser);
	}

	/**
	 * Adds a new seed, if it wasn't already processed.
	 * 
	 * @param uri the uri
	 * @param method the http method used for fetching the resource
	 */
	protected void addSeed(URI uri, String method) {
		// Check if the uri was processed already
		String visitedURI;
		try {
			visitedURI = URLCanonicalizer.buildCleanedParametersURIRepresentation(uri, spider.getSpiderParam()
					.getHandleParameters(), spider.getSpiderParam().isHandleODataParametersVisited());
		} catch (URIException e) {
			return;
		}
		synchronized (visitedGet) {
			if (visitedGet.contains(visitedURI)) {
				log.debug("URI already visited: " + visitedURI);
				return;
			} else {
				visitedGet.add(visitedURI);
			}
		}
		// Create and submit the new task
		SpiderTask task = new SpiderTask(spider, uri, 0, method);
		spider.submitTask(task);
		// Add the uri to the found list
		spider.notifyListenersFoundURI(uri.toString(), method, FetchStatus.SEED);
	}

	/**
	 * Gets the fetch filters used by the spider during the spidering process.
	 * 
	 * @return the fetch filters
	 */
	protected LinkedList<FetchFilter> getFetchFilters() {
		return fetchFilters;
	}

	/**
	 * Adds a new fetch filter to the spider.
	 * 
	 * @param filter the filter
	 */
	public void addFetchFilter(FetchFilter filter) {
		fetchFilters.add(filter);
	}

	/**
	 * Gets the parses the filters.
	 * 
	 * @return the parses the filters
	 */
	protected LinkedList<ParseFilter> getParseFilters() {
		return parseFilters;
	}

	/**
	 * Adds the parse filter to the spider controller.
	 * 
	 * @param filter the filter
	 */
	public void addParseFilter(ParseFilter filter) {
		parseFilters.add(filter);
	}

	/**
	 * Clears the previous process.
	 */
	public void reset() {
		visitedGet.clear();
		visitedPost.clear();
	}

	/**
	 * Gets the instances for the parsers.
	 * 
	 * @return the parser
	 */
	public List<SpiderParser> getParsers(HttpMessage message) {

		// If parsing of robots.txt is enabled, try to see if it's necessary
		if (spider.getSpiderParam().isParseRobotsTxt()) {
			// Get the path of the file
			String path = null;
			try {
				path = message.getRequestHeader().getURI().getPath();
				log.debug("Getting parsers for " + path);
			} catch (URIException e) {
			}
			// If it's a robots.txt file
			if (path != null && path.equalsIgnoreCase("/robots.txt")) {
				log.info("Parsing a robots.txt resource...");
				SpiderParser parser = new SpiderRobotstxtParser(spider.getSpiderParam());
				parser.addSpiderParserListener(this);
				List<SpiderParser> robotsParsers = new LinkedList<>();
				robotsParsers.add(parser);
				return robotsParsers;
			}
		}

		// If the response is a HTTP redirect message
		if (HttpStatusCode.isRedirection(message.getResponseHeader().getStatusCode())) {
			log.info("Parsing a HTTP Redirect message...");
			SpiderRedirectParser parser = new SpiderRedirectParser();
			parser.addSpiderParserListener(this);
			List<SpiderParser> redirectParsers = new LinkedList<>();
			redirectParsers.add(parser);
			return redirectParsers;
		}

		// If it reached this point, it is definitely text
		if (message.getResponseHeader().isHtml()) {
			return htmlParsers;
		} else if (message.getResponseHeader().isXml()) {
			return xmlParsers;
		} else {
			// Parsing non-HTML text resource.
			return txtParsers;
		}
	}

	@Override
	public void resourceURIFound(HttpMessage responseMessage, int depth, String uri, boolean shouldIgnore) {
		log.debug("New resource found: " + uri);

		if (uri == null) {
			return;
		}

		// Create the uri
		URI uriV = createURI(uri);
		if (uriV == null) {
			return;
		}

		// Check if the uri was processed already
		String visitedURI;
		try {
			visitedURI = URLCanonicalizer.buildCleanedParametersURIRepresentation(uriV, spider.getSpiderParam()
					.getHandleParameters(), spider.getSpiderParam().isHandleODataParametersVisited());
		} catch (URIException e) {
			return;
		}
		synchronized (visitedGet) {
			if (visitedGet.contains(visitedURI)) {
				// log.debug("URI already visited: " + visitedURI);
				return;
			} else {
				visitedGet.add(visitedURI);
			}
		}

		// Check if any of the filters disallows this uri
		for (FetchFilter f : fetchFilters) {
			FetchStatus s = f.checkFilter(uriV);
			if (s != FetchStatus.VALID) {
				log.info("URI: " + uriV + " was filtered by a filter with reason: " + s);
				spider.notifyListenersFoundURI(uri, HttpRequestHeader.GET, s);
				return;
			}
		}

		// Check if should be ignored and not fetched
		if (shouldIgnore) {
			log.info("URI: " + uriV + " is valid, but will not be fetched, by parser reccommendation.");
			spider.notifyListenersFoundURI(uri, HttpRequestHeader.GET, FetchStatus.VALID);
			return;
		}

		spider.notifyListenersFoundURI(uri, HttpRequestHeader.GET, FetchStatus.VALID);

		// Submit the task
		SpiderTask task = new SpiderTask(spider, uriV, depth, HttpRequestHeader.GET);
		spider.submitTask(task);
	}

	@Override
	public void resourceURIFound(HttpMessage responseMessage, int depth, String uri) {
		resourceURIFound(responseMessage, depth, uri, false);
	}

	@Override
	public void resourcePostURIFound(HttpMessage responseMessage, int depth, String uri, String requestBody) {
		log.debug("New POST resource found: " + uri);

		// Check if the uri was processed already
		synchronized (visitedPost) {
			if (visitedPost.contains(uri)) {
				log.debug("URI already visited: " + uri);
				return;
			} else {
				visitedPost.add(uri);
			}
		}

		// Create the uri
		URI uriV = createURI(uri);
		if (uriV == null) {
			return;
		}

		// Check if any of the filters disallows this uri
		for (FetchFilter f : fetchFilters) {
			FetchStatus s = f.checkFilter(uriV);
			if (s != FetchStatus.VALID) {
				log.info("URI: " + uriV + " was filtered by a filter with reason: " + s);
				spider.notifyListenersFoundURI(uri, HttpRequestHeader.POST, s);
				return;
			}
		}

		spider.notifyListenersFoundURI(uri, HttpRequestHeader.POST, FetchStatus.VALID);

		// Submit the task
		SpiderTask task = new SpiderTask(spider, uriV, depth, HttpRequestHeader.POST, requestBody);
		spider.submitTask(task);

	}

	/**
	 * Creates the {@link URI} starting from the uri string. First it tries to convert it into a
	 * String considering it's already encoded and, if it fails, tries to create it considering it's
	 * not encoded.
	 * 
	 * @param uri the string of the uri
	 * @return the URI, or null if an error occured and the URI could not be constructed.
	 */
	private URI createURI(String uri) {
		URI uriV = null;
		try {
			// Try to see if we can create the URI, considering it's encoded.
			uriV = new URI(uri, true);
		} catch (URIException e) {
			// An error occured, so try to create the URI considering it's not encoded.
			try {
				log.debug("Second try...");
				uriV = new URI(uri, false);
			} catch (Exception ex) {
				log.error("Error while converting to uri: " + uri, ex);
				return null;
			}
			// A non URIException occured, so just ignore the URI
		} catch (Exception e) {
			log.error("Error while converting to uri: " + uri, e);
			return null;
		}
		return uriV;
	}
}
