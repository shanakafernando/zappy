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
package org.zaproxy.zap.extension.ascan;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import net.sf.json.JSONObject;

import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.log4j.Logger;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.HostProcess;
import org.parosproxy.paros.core.scanner.ScannerListener;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.model.Session;
import org.parosproxy.paros.model.SiteNode;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.alert.ExtensionAlert;
import org.zaproxy.zap.extension.api.ApiAction;
import org.zaproxy.zap.extension.api.ApiException;
import org.zaproxy.zap.extension.api.ApiImplementor;
import org.zaproxy.zap.extension.api.ApiResponse;
import org.zaproxy.zap.extension.api.ApiResponseElement;
import org.zaproxy.zap.extension.api.ApiResponseList;
import org.zaproxy.zap.extension.api.ApiView;

public class ActiveScanAPI extends ApiImplementor implements ScannerListener {

    private static Logger log = Logger.getLogger(ActiveScanAPI.class);

	private static final String PREFIX = "ascan";
    private static final String ACTION_SCAN = "scan";

	private static final String ACTION_EXCLUDE_FROM_SCAN = "excludeFromScan";
	private static final String ACTION_CLEAR_EXCLUDED_FROM_SCAN = "clearExcludedFromScan";
    
	private static final String VIEW_STATUS = "status";
	private static final String VIEW_EXCLUDED_FROM_SCAN = "excludedFromScan";

	private static final String PARAM_URL = "url";
	private static final String PARAM_REGEX = "regex";
	private static final String PARAM_RECURSE = "recurse";
    private static final String PARAM_JUST_IN_SCOPE = "inScopeOnly";

	private ExtensionActiveScan extension;
	private ActiveScan activeScan = null;
	private int progress = 0;
	
	public ActiveScanAPI (ExtensionActiveScan extension) {
		this.extension = extension;
        this.addApiAction(new ApiAction(ACTION_SCAN, new String[] {PARAM_URL}, new String[] {PARAM_RECURSE, PARAM_JUST_IN_SCOPE}));
		this.addApiAction(new ApiAction(ACTION_CLEAR_EXCLUDED_FROM_SCAN));
		this.addApiAction(new ApiAction(ACTION_EXCLUDE_FROM_SCAN, new String[] {PARAM_REGEX}));

		this.addApiView(new ApiView(VIEW_STATUS));
		this.addApiView(new ApiView(VIEW_EXCLUDED_FROM_SCAN));

	}
	
	@Override
	public String getPrefix() {
		return PREFIX;
	}

	@Override
	public ApiResponse handleApiAction(String name, JSONObject params) throws ApiException {
		log.debug("handleApiAction " + name + " " + params.toString());
		if (ACTION_SCAN.equals(name)) {
			String url = params.getString(PARAM_URL);
			if (url == null || url.length() == 0) {
				throw new ApiException(ApiException.Type.MISSING_PARAMETER, PARAM_URL);
			}
		    scanURL(params.getString(PARAM_URL), this.getParam(params, PARAM_RECURSE, true), this.getParam(params, PARAM_JUST_IN_SCOPE, false));

		} else if (ACTION_CLEAR_EXCLUDED_FROM_SCAN.equals(name)) {
			try {
				Session session = Model.getSingleton().getSession();
				session.setExcludeFromScanRegexs(new ArrayList<String>());
			} catch (SQLException e) {
				throw new ApiException(ApiException.Type.INTERNAL_ERROR, e.getMessage());
			}
		} else if (ACTION_EXCLUDE_FROM_SCAN.equals(name)) {
			String regex = params.getString(PARAM_REGEX);
			try {
				Session session = Model.getSingleton().getSession();
				session.addExcludeFromScanRegexs(regex);
			} catch (Exception e) {
				throw new ApiException(ApiException.Type.BAD_FORMAT, PARAM_REGEX);
			}
		} else {
			throw new ApiException(ApiException.Type.BAD_ACTION);
		}
		return ApiResponseElement.OK;
	}

	private void scanURL(String url, boolean scanChildren, boolean scanJustInScope) throws ApiException {
		
		if (activeScan != null && ! activeScan.isStopped()) {
			throw new ApiException(ApiException.Type.SCAN_IN_PROGRESS);
		}

		// Try to find node
		SiteNode startNode;
		try {
			startNode = Model.getSingleton().getSession().getSiteTree().findNode(new URI(url, true));
			if (startNode == null) {
				throw new ApiException(ApiException.Type.URL_NOT_FOUND);
			}
		} catch (URIException e) {
			throw new ApiException(ApiException.Type.URL_NOT_FOUND);
		}

		activeScan = new ActiveScan(url, extension.getScannerParam(), 
				extension.getModel().getOptionsParam().getConnectionParam(), null);
		
		progress = 0;
        activeScan.setJustScanInScope(scanJustInScope);
		activeScan.addScannerListener(this);
		activeScan.setStartNode(startNode);
        activeScan.setScanChildren(scanChildren);
		activeScan.start();
	}

	@Override
	public ApiResponse handleApiView(String name, JSONObject params)
			throws ApiException {
		ApiResponse result;

		if (VIEW_STATUS.equals(name)) {
			result = new ApiResponseElement(name, String.valueOf(progress));
		} else if (VIEW_EXCLUDED_FROM_SCAN.equals(name)) {
			result = new ApiResponseList(name);
			Session session = Model.getSingleton().getSession();
			List<String> regexs = session.getExcludeFromScanRegexs();
			for (String regex : regexs) {
				((ApiResponseList)result).addItem(new ApiResponseElement("regex", regex));
			}
		} else {
			throw new ApiException(ApiException.Type.BAD_VIEW);
		}
		return result;
	}
	
	@Override
	public void alertFound(Alert alert) {
		ExtensionAlert extAlert = (ExtensionAlert) Control.getSingleton().getExtensionLoader().getExtension(ExtensionAlert.NAME);
		if (extAlert != null) {
			extAlert.alertFound(alert, alert.getHistoryRef());
		}
	}

	@Override
	public void hostComplete(String hostAndPort) {
        activeScan.reset();
	}

	@Override
	public void hostNewScan(String hostAndPort, HostProcess hostThread) {
	}

	@Override
	public void hostProgress(String hostAndPort, String msg, int percentage) {
		this.progress = percentage;
	}

	@Override
	public void notifyNewMessage(HttpMessage msg) {
	}

	@Override
	public void scannerComplete() {
	}
}
