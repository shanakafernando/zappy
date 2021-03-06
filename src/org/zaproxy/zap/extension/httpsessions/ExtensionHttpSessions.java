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
package org.zaproxy.zap.extension.httpsessions;

import java.net.HttpCookie;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control.Mode;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.extension.SessionChangedListener;
import org.parosproxy.paros.model.Session;
import org.parosproxy.paros.model.SiteNode;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpSender;
import org.zaproxy.zap.extension.api.API;
import org.zaproxy.zap.network.HttpSenderListener;
import org.zaproxy.zap.view.ScanPanel;
import org.zaproxy.zap.view.SiteMapListener;
import org.zaproxy.zap.view.SiteMapTreeCellRenderer;

/**
 * The HttpSessions Extension handles the existing http sessions on the existing site. It allows the
 * management and usage of multiple sessions per site and also handles the session tokens for each
 * of them.
 * 
 * <p>
 * Whenever referring to a particular site, the string that is identifying it is constructed from
 * the site's URI and has to follow these rules:
 * <ul>
 * <li>no leading protocol (e.g. 'http'), colon (':') and double slashes ('//')</li>
 * <li>the port is added in the end after colon</li>
 * <li>lower-case</li>
 * </ul>
 * An example of a method performing these changes on an URI is
 * {@link ScanPanel#cleanSiteName(String, boolean)} .
 * </p>
 * 
 */
public class ExtensionHttpSessions extends ExtensionAdaptor implements SessionChangedListener, SiteMapListener,
		HttpSenderListener {

	/** The Constant NAME. */
	public static final String NAME = "ExtensionHttpSessions";

	/** The Constant log. */
	private static final Logger log = Logger.getLogger(ExtensionHttpSessions.class);

	/** The http sessions panel. */
	private HttpSessionsPanel httpSessionsPanel;

	/** The options http sessions panel. */
	private OptionsHttpSessionsPanel optionsHttpSessionsPanel;

	/** The map of sessions corresponding to each site. */
	private Map<String, HttpSessionsSite> sessions;

	/** The map of session tokens corresponding to each site. */
	private Map<String, LinkedHashSet<String>> sessionTokens;

	/**
	 * The map of default tokens that were removed by the user for some sites and should not be
	 * detected again as session tokens.
	 */
	private Map<String, HashSet<String>> removedDefaultTokens;

	/** The http sessions extension's parameters. */
	private HttpSessionsParam param;

	/** The popup menu used to set the active session. */
	private PopupMenuSetActiveSession popupMenuSetActiveSession;

	/** The popup menu used to unset the active session. */
	private PopupMenuUnsetActiveSession popupMenuUnsetActiveSession;

	/** The popup menu used to remove a session. */
	private PopupMenuRemoveSession popupMenuRemoveSession;

	/**
	 * Instantiates a new extension http sessions.
	 */
	public ExtensionHttpSessions() {
		super(NAME);
		initialize();
	}

	/**
	 * Initialize.
	 */
	private void initialize() {
		this.setOrder(68);
	}

	@Override
	public String getAuthor() {
		return Constant.ZAP_TEAM;
	}

	@Override
	public String getDescription() {
		return Constant.messages.getString("httpsessions.desc");
	}

	@Override
	public URL getURL() {
		try {
			return new URL(Constant.ZAP_HOMEPAGE);
		} catch (MalformedURLException e) {
			return null;
		}
	}

	@Override
	public void init() {
		super.init();
		this.sessionTokens = new HashMap<>();
	}

	@Override
	public void hook(ExtensionHook extensionHook) {
		super.hook(extensionHook);

		// Register the parameters
		extensionHook.addOptionsParamSet(getParam());

		extensionHook.addSessionListener(this);
		extensionHook.addSiteMapListener(this);
		HttpSender.addListener(this);

		if (getView() != null) {

			// Hook the panels
			extensionHook.getHookView().addStatusPanel(getHttpSessionsPanel());
			extensionHook.getHookView().addOptionPanel(getOptionsHttpSessionsPanel());

			// Hook the popup menus
			extensionHook.getHookMenu().addPopupMenuItem(getPopupMenuSetActiveSession());
			extensionHook.getHookMenu().addPopupMenuItem(getPopupMenuUnsetActiveSession());
			extensionHook.getHookMenu().addPopupMenuItem(getPopupMenuRemoveSession());
		}

		// Register as an API implementor
		HttpSessionsAPI httpSessionsApi = new HttpSessionsAPI(this);
		API.getInstance().registerApiImplementor(httpSessionsApi);
	}

	/**
	 * Gets the options panel for this extension.
	 * 
	 * @return the options session panel
	 */
	private OptionsHttpSessionsPanel getOptionsHttpSessionsPanel() {
		if (optionsHttpSessionsPanel == null) {
			optionsHttpSessionsPanel = new OptionsHttpSessionsPanel();
		}
		return optionsHttpSessionsPanel;
	}

	/**
	 * Gets the popup menu set active session.
	 * 
	 * @return the popup menu set active session
	 */
	private PopupMenuSetActiveSession getPopupMenuSetActiveSession() {
		if (popupMenuSetActiveSession == null) {
			popupMenuSetActiveSession = new PopupMenuSetActiveSession();
			popupMenuSetActiveSession.setExtension(this);
		}
		return popupMenuSetActiveSession;
	}

	/**
	 * Gets the popup menu used to delete a session.
	 * 
	 * @return the popup menu used to delete a session
	 */
	private PopupMenuRemoveSession getPopupMenuRemoveSession() {
		if (popupMenuRemoveSession == null) {
			popupMenuRemoveSession = new PopupMenuRemoveSession();
			popupMenuRemoveSession.setExtension(this);
		}
		return popupMenuRemoveSession;
	}

	/**
	 * Gets the popup menu to unset active session.
	 * 
	 * @return the popup menu unset active session
	 */
	private PopupMenuUnsetActiveSession getPopupMenuUnsetActiveSession() {
		if (popupMenuUnsetActiveSession == null) {
			popupMenuUnsetActiveSession = new PopupMenuUnsetActiveSession();
			popupMenuUnsetActiveSession.setExtension(this);
		}
		return popupMenuUnsetActiveSession;
	}

	/**
	 * Gets the parameters (options) for this extension and related classes.
	 * 
	 * @return the param
	 */
	public HttpSessionsParam getParam() {
		if (param == null) {
			param = new HttpSessionsParam();
		}
		return param;
	}

	/**
	 * Checks if a particular token is part of the default session tokens set by the user using the
	 * options panel. The default session tokens are valid for all sites. The check is being
	 * performed in a lower-case manner, as default session tokens are case-insensitive.
	 * 
	 * @param token the token
	 * @return true, if it is a default session token
	 */
	public boolean isDefaultSessionToken(String token) {
		if (getParam().getDefaultTokensEnabled().contains(token.toLowerCase(Locale.ENGLISH)))
			return true;
		return false;
	}

	/**
	 * Checks if a particular default session token was removed by an user as a session token for a
	 * site.
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation.
	 * @param token the token
	 * @return true, if it is a previously removed default session token
	 */
	private boolean isRemovedDefaultSessionToken(String site, String token) {
		if (removedDefaultTokens == null)
			return false;
		HashSet<String> removed = removedDefaultTokens.get(site);
		if (removed == null || !removed.contains(token))
			return false;
		return true;
	}

	/**
	 * Marks a default session token as removed for a particular site.
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation.
	 * @param token the token
	 */
	private void markRemovedDefaultSessionToken(String site, String token) {
		if (removedDefaultTokens == null)
			removedDefaultTokens = new HashMap<>(1);
		HashSet<String> removedSet = removedDefaultTokens.get(site);
		if (removedSet == null) {
			removedSet = new HashSet<>(1);
			removedDefaultTokens.put(site, removedSet);
		}
		removedSet.add(token);
	}

	/**
	 * Unmarks a default session token as removed for a particular site.
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation.
	 * @param token the token
	 */
	private void unmarkRemovedDefaultSessionToken(String site, String token) {
		if (removedDefaultTokens == null)
			return;
		HashSet<String> removed = removedDefaultTokens.get(site);
		if (removed == null)
			return;
		removed.remove(token);
	}

	/**
	 * Checks if a particular token is a session token name for a particular site.
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation. However, if the protocol is
	 *            missing, a default protocol of 80 is used.
	 * @param token the token
	 * @return true, if it is session token
	 */
	public boolean isSessionToken(String site, String token) {
		// Add a default port
		if (!site.contains(":")) {
			site = site + (":80");
		}
		HashSet<String> siteTokens = sessionTokens.get(site);
		if (siteTokens == null)
			return false;
		return siteTokens.contains(token);
	}

	/**
	 * Adds a new session token for a particular site.
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation. However, if the protocol is
	 *            missing, a default protocol of 80 is used.
	 * @param token the token
	 */
	public void addHttpSessionToken(String site, String token) {
		// Add a default port
		if (!site.contains(":")) {
			site = site + (":80");
		}
		LinkedHashSet<String> siteTokens = sessionTokens.get(site);
		if (siteTokens == null) {
			siteTokens = new LinkedHashSet<>();
			sessionTokens.put(site, siteTokens);
		}
		log.info("Added new session token for site '" + site + "': " + token);
		siteTokens.add(token);
		// If the session token is a default token and was previously marked as remove, undo that
		unmarkRemovedDefaultSessionToken(site, token);
	}

	/**
	 * Removes a particular session token for a site.
	 * <p>
	 * All the existing sessions are cleaned up:
	 * <ul>
	 * <li>if there are no more session tokens, all session are deleted</li>
	 * <li>in every existing session, the value for the deleted token is removed</li>
	 * <li>if there is a session with no values for the remaining session tokens, it is deleted</li>
	 * <li>if, after deletion, there are duplicate sessions, they are merged</li>
	 * </ul>
	 * </p>
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation. However, if the protocol is
	 *            missing, a default protocol of 80 is used.
	 * @param token the token
	 */
	public void removeHttpSessionToken(String site, String token) {
		// Add a default port
		if (!site.contains(":")) {
			site = site + (":80");
		}
		HashSet<String> siteTokens = sessionTokens.get(site);
		if (siteTokens != null) {
			// Remove the token from the tokens associated with the site
			siteTokens.remove(token);
			if (siteTokens.isEmpty())
				sessionTokens.remove(site);
			// Cleanup the existing sessions
			this.getHttpSessionsSite(site).cleanupSessionToken(token);
		}
		// If the token is a default session token, mark it as removed for the Site, so it will not
		// be detected again and added as a session token
		if (isDefaultSessionToken(token))
			markRemovedDefaultSessionToken(site, token);
		log.info("Removed session token for site '" + site + "': " + token);
	}

	/**
	 * Gets the set of session tokens for a particular site.
	 * <p>
	 * The set of session tokens returned is read-only view of the internal session tokens and any
	 * modifications will result in {@link UnsupportedOperationException}. The current
	 * implementation of the set is using {@link LinkedHashSet}, thus iterating through the set is
	 * done in constant time.
	 * </p>
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation. However, if the protocol is
	 *            missing, a default protocol of 80 is used.
	 * @return the read-only session tokens set, if any have been set, or null, if there are no
	 *         session tokens for this site
	 */
	public final Set<String> getHttpSessionTokens(String site) {
		// Add a default port
		if (!site.contains(":")) {
			site = site + (":80");
		}
		Set<String> internalSet = sessionTokens.get(site);
		if (internalSet == null)
			return null;
		return Collections.unmodifiableSet(internalSet);
	}

	/**
	 * Gets the http sessions panel.
	 * 
	 * @return the http sessions panel
	 */
	protected HttpSessionsPanel getHttpSessionsPanel() {
		if (httpSessionsPanel == null) {
			httpSessionsPanel = new HttpSessionsPanel(this);
		}
		return httpSessionsPanel;
	}

	/**
	 * Gets the http sessions for a particular site. If it doesn't exist, it is created.
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation. However, if the protocol is
	 *            missing, a default protocol of 80 is used.
	 * @return the http sessions site container
	 */
	protected HttpSessionsSite getHttpSessionsSite(String site) {
		return getHttpSessionsSite(site, true);
	}

	/**
	 * Gets the http sessions for a particular site. The behaviour when a {@link HttpSessionsSite}
	 * does not exist is defined by the {@code createIfNeeded} parameter.
	 * 
	 * @param site the site. This parameter has to be formed as defined in the
	 *            {@link ExtensionHttpSessions} class documentation. However, if the protocol is
	 *            missing, a default protocol of 80 is used.
	 * @param createIfNeeded whether a new {@link HttpSessionsSite} object is created if one does
	 *            not exist
	 * @return the http sessions site container, or null one does not exist and createIfNeeded is
	 *         false
	 * 
	 */
	protected HttpSessionsSite getHttpSessionsSite(String site, boolean createIfNeeded) {
		if (sessions == null) {
			if (!createIfNeeded)
				return null;
			sessions = new HashMap<>();
		}
		// Add a default port
		if (!site.contains(":")) {
			site = site + (":80");
		}
		HttpSessionsSite hss = sessions.get(site);
		if (hss == null) {
			if (!createIfNeeded)
				return null;
			hss = new HttpSessionsSite(this, site);
			sessions.put(site, hss);
		}
		return hss;
	}

	@Override
	public void nodeSelected(SiteNode node) {
		// Event from SiteMapListenner
		this.getHttpSessionsPanel().nodeSelected(node);
	}

	@Override
	public void onReturnNodeRendererComponent(SiteMapTreeCellRenderer component, boolean leaf, SiteNode value) {
	}

	@Override
	public void sessionChanged(Session session) {
	}

	@Override
	public void sessionAboutToChange(Session session) {
	}

	@Override
	public void sessionScopeChanged(Session session) {
	}

	@Override
	public void sessionModeChanged(Mode mode) {
	}

	@Override
	public int getListenerOrder() {
		return 1;
	}

	@Override
	public void onHttpRequestSend(HttpMessage msg, int initiator) {
		// Check if we know the site and add it otherwise
		String site = msg.getRequestHeader().getHostName() + ":" + msg.getRequestHeader().getHostPort();

		site = ScanPanel.cleanSiteName(site, true);
		this.getHttpSessionsPanel().addSite(site);

		// Check if it's enabled for proxy only
		if (getParam().isEnabledProxyOnly() && initiator != HttpSender.PROXY_INITIATOR)
			return;

		// Check for default tokens in request messages
		List<HttpCookie> requestCookies = msg.getRequestHeader().getHttpCookies();
		for (HttpCookie cookie : requestCookies)
			// If it's a default session token and it is not already marked as session token and was
			// not previously removed by the user
			if (this.isDefaultSessionToken(cookie.getName()) && !this.isSessionToken(site, cookie.getName())
					&& !this.isRemovedDefaultSessionToken(site, cookie.getName()))
				this.addHttpSessionToken(site, cookie.getName());

		// Forward the request for proper processing
		HttpSessionsSite session = this.getHttpSessionsSite(site);
		session.processHttpRequestMessage(msg);
	}

	@Override
	public void onHttpResponseReceive(HttpMessage msg, int initiator) {
		if (initiator == HttpSender.ACTIVE_SCANNER_INITIATOR || initiator == HttpSender.SPIDER_INITIATOR) {
			// Not a session we care about
			return;
		}

		// Check if we know the site and add it otherwise
		String site = msg.getRequestHeader().getHostName() + ":" + msg.getRequestHeader().getHostPort();

		site = ScanPanel.cleanSiteName(site, true);
		this.getHttpSessionsPanel().addSite(site);

		// Check if it's enabled for proxy only
		if (getParam().isEnabledProxyOnly() && initiator != HttpSender.PROXY_INITIATOR) {
			return;
		}

		// Check for default tokens set in response messages
		List<HttpCookie> responseCookies = msg.getResponseHeader().getHttpCookies();
		for (HttpCookie cookie : responseCookies)
			// If it's a default session token and it is not already marked as session token and was
			// not previously removed by the user
			if (this.isDefaultSessionToken(cookie.getName()) && !this.isSessionToken(site, cookie.getName())
					&& !this.isRemovedDefaultSessionToken(site, cookie.getName()))
				this.addHttpSessionToken(site, cookie.getName());

		// Forward the request for proper processing
		HttpSessionsSite sessionsSite = this.getHttpSessionsSite(site);
		sessionsSite.processHttpResponseMessage(msg);
	}

}
