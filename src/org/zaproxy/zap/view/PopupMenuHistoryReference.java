/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Copyright 2010 psiinon@gmail.com
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
package org.zaproxy.zap.view;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JList;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.apache.log4j.Logger;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.control.Control.Mode;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.extension.ExtensionPopupMenuItem;
import org.parosproxy.paros.model.HistoryReference;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.model.Session;
import org.parosproxy.paros.model.SiteNode;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.alert.AlertNode;
import org.zaproxy.zap.extension.ascan.ActiveScanPanel;
import org.zaproxy.zap.extension.bruteforce.BruteForceItem;
import org.zaproxy.zap.extension.bruteforce.BruteForcePanel;
import org.zaproxy.zap.extension.fuzz.FuzzerPanel;
import org.zaproxy.zap.extension.search.SearchResult;

public abstract class PopupMenuHistoryReference extends ExtensionPopupMenuItem {

	public static enum Invoker {sites, history, alerts, ascan, search, fuzz, bruteforce, hreftable};
	
	private static final long serialVersionUID = 1L;
	private JTree treeInvoker = null;
    private JList<?> listInvoker = null;
    private HistoryReferenceTable hrefTableInvoker = null;
    private Invoker lastInvoker = null;
    private boolean multiSelect = false;

    private static final Logger log = Logger.getLogger(PopupMenuHistoryReference.class);

    /**
     * @param label
     */
    public PopupMenuHistoryReference(String label) {
    	this(label, false);
    }

    /**
     * @param label
     */
    public PopupMenuHistoryReference(String label, boolean multiSelect) {
        super(label);
        this.setText(label);
        this.multiSelect = multiSelect;
        this.initialize();
    }

    /**
     * Returns the last invoker.
     *
     * @return the last invoker.
     */
    protected Invoker getLastInvoker() {
        return lastInvoker;
    }

	/**
	 * This method initializes this
	 */
	protected void initialize() {

        this.addActionListener(new java.awt.event.ActionListener() { 

        	@Override
        	public void actionPerformed(java.awt.event.ActionEvent e) {
        		log.debug("actionPerformed " + lastInvoker.name() + " " + e.getActionCommand());

        	    try {
        	    	if (multiSelect) {
            	    	performActions(getSelectedHistoryReferences());
        	    		
        	    	} else {
	            	    HistoryReference ref = getSelectedHistoryReference();
		        		if (ref != null) {
		            	    try {
		            	    	performAction(ref);
		                    } catch (Exception e1) {
		    					log.error(e1.getMessage(), e1);
		                    }
		        		} else {
		        			log.error("PopupMenuHistoryReference invoker " + lastInvoker + " failed to get history ref");
		        		}
        	    	}
				} catch (Exception e2) {
					log.error(e2.getMessage(), e2);
				}
        	}
        });
	}
	
	private HistoryReference getSelectedHistoryReference() {
		HttpMessage msg = null;
	    HistoryReference ref = null;
    	try {
    		switch (lastInvoker) {
    		case sites:
    		    SiteNode sNode = (SiteNode) treeInvoker.getLastSelectedPathComponent();
        	    ref = sNode.getHistoryReference();
                break;

    		case ascan:
            case fuzz:
    		case history:
        	    ref = (HistoryReference) listInvoker.getSelectedValue();
				break;

    		case alerts:
    			AlertNode aNode = (AlertNode) treeInvoker.getLastSelectedPathComponent();
        	    if (aNode.getUserObject() != null) {
        	        if (aNode.getUserObject() instanceof Alert) {
        	            Alert alert = (Alert) aNode.getUserObject();
        	            ref = alert.getHistoryRef();
        	        }
        	    }
				break;
    		case search:
        	    SearchResult sr = (SearchResult) listInvoker.getSelectedValue();
        	    if (sr != null) {
        	    	msg = sr.getMessage();
            	    if (msg != null) {
            	    	ref = msg.getHistoryRef();
            	    }
        	    }
				break;
    		case bruteforce:
    	    	BruteForceItem bfi = (BruteForceItem) listInvoker.getSelectedValue();
    	    	ref = new HistoryReference(bfi.getHistoryId());
				break;
    		case hreftable:
    			ref = hrefTableInvoker.getSelectedValue();
    			break;
    		}
		} catch (Exception e2) {
			log.error(e2.getMessage(), e2);
		}
    	return ref;
		
	}
	
	private List<HistoryReference> getSelectedHistoryReferences() {
	    List <HistoryReference> refs = new ArrayList<>();
	    TreePath[] treePaths = null;
	    List<?> selectedValues = null;
		HttpMessage msg = null;
    	try {
    		switch (lastInvoker) {
    		case sites:
    		    treePaths = treeInvoker.getSelectionPaths();
    		    if (treePaths != null) {
	                for (TreePath path : treePaths) {
	                    SiteNode node = (SiteNode) path.getLastPathComponent();
	                    refs.add(node.getHistoryReference());
	                }
    		    }
                break;

    		case ascan:
            case fuzz:
    		case history:
        	    selectedValues = listInvoker.getSelectedValuesList();
        	    if (selectedValues != null) {
        	    	for (Object obj : selectedValues) {
        	    		refs.add((HistoryReference)obj);
        	    	}
        	    }
				break;

    		case alerts:
    		    // Only support single items
    			AlertNode aNode = (AlertNode) treeInvoker.getLastSelectedPathComponent();
        	    if (aNode.getUserObject() != null) {
        	        if (aNode.getUserObject() instanceof Alert) {
        	            Alert alert = (Alert) aNode.getUserObject();
        	            refs.add(alert.getHistoryRef());
        	        }
        	    }
				break;
    		case search:
        	    selectedValues = listInvoker.getSelectedValuesList();
        	    if (selectedValues != null) {
        	    	for (Object obj : selectedValues) {
                	    SearchResult sr = (SearchResult) obj;
                	    if (sr != null) {
                	    	msg = sr.getMessage();
                    	    if (msg != null) {
                    	    	refs.add(msg.getHistoryRef());
                    	    }
                	    }
        	    	}
        	    }
				break;
    		case bruteforce:
        	    selectedValues = listInvoker.getSelectedValuesList();
        	    if (selectedValues != null) {
        	    	for (Object obj : selectedValues) {
                	    BruteForceItem bfi = (BruteForceItem)obj;
            	    	refs.add(new HistoryReference(bfi.getHistoryId()));
        	    	}
        	    }
				break;
    		case hreftable:
    			refs = hrefTableInvoker.getSelectedValues();
    			break;
    		}
		} catch (Exception e2) {
			log.error(e2.getMessage(), e2);
		}
    	return refs;
		
	}
	
	@Override
    public boolean isEnableForComponent(Component invoker) {
    	boolean display = false;
    	if (invoker.getName() == null) {
    		return false;
    	}
    	
        if (invoker.getName().equals("ListLog")) {
        	this.lastInvoker = Invoker.history;
            this.listInvoker = (JList<?>) invoker;
            this.setEnabled(isEnabledForHistoryReferences(getSelectedHistoryReferences()));
            display = true;
        } else if (invoker instanceof JTree && invoker.getName().equals("treeSite")) {
        	this.lastInvoker = Invoker.sites;
        	this.treeInvoker = (JTree) invoker;
            this.setEnabled(isEnabledForHistoryReferences(getSelectedHistoryReferences()));
            display = true;
        } else if (invoker.getName().equals("treeAlert")) {
        	this.lastInvoker = Invoker.alerts;
        	this.treeInvoker = (JTree) invoker;
        	JTree tree = (JTree) invoker;
            if (tree.getLastSelectedPathComponent() != null) {
            	if (tree.getSelectionCount() > 1) {
                	// Note - the Alerts tree only supports single selections
                    this.setEnabled(false);
            	} else {
	                DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
	                if (!node.isRoot() && node.getUserObject() != null) {
	                    this.setEnabled(isEnabledForHistoryReference(getSelectedHistoryReferences().get(0)));
	                } else {
	                    this.setEnabled(false);
	                }
            	}
            }
            display = true;
        } else if (invoker.getName().equals("listSearch")) {
        	this.lastInvoker = Invoker.search;
            this.listInvoker = (JList<?>) invoker;
            this.setEnabled(isEnabledForHistoryReferences(getSelectedHistoryReferences()));
            display = true;
        } else if (invoker.getName().equals(ActiveScanPanel.PANEL_NAME)) {
        	this.lastInvoker = Invoker.ascan;
            this.listInvoker = (JList<?>) invoker;
            this.setEnabled(isEnabledForHistoryReferences(getSelectedHistoryReferences()));
            display = true;
        } else if (invoker.getName().equals(FuzzerPanel.PANEL_NAME)) {
        	this.lastInvoker = Invoker.fuzz;
            this.listInvoker = (JList<?>) invoker;
            this.setEnabled(isEnabledForHistoryReferences(getSelectedHistoryReferences()));
            display = true;
        } else if (invoker.getName().equals(BruteForcePanel.PANEL_NAME)) {
        	this.lastInvoker = Invoker.bruteforce;
            this.listInvoker = (JList<?>) invoker;
            this.setEnabled(isEnabledForHistoryReferences(getSelectedHistoryReferences()));
            display = true;
        } else if (invoker instanceof HistoryReferenceTable) {
        	this.lastInvoker = Invoker.hreftable;
            this.hrefTableInvoker = (HistoryReferenceTable) invoker;
            this.setEnabled(isEnabledForHistoryReferences(getSelectedHistoryReferences()));
            display = true;
        } else {
        	log.debug("Popup " + this.getName() + 
        			" not enabled for panel " + invoker.getName() + 
        			" class " + invoker.getClass().getName());
        }

        if (display) {
        	if (this.isEnabled() && ! this.isSafe() && Control.getSingleton().getMode().equals(Mode.protect)) {
        		boolean inScope = true;
        		Session session = Model.getSingleton().getSession();
        		for (HistoryReference href : getSelectedHistoryReferences()) {
        			if ( ! session.isInScope(href)) {
        				inScope = false;
        				break;
        			}
        		}
        		if (!inScope) {
        			// Not safe and not in scope while in protected mode
        			this.setEnabled(false);
        		}
        	}
        	return this.isEnableForInvoker(lastInvoker);
        }
       
        return false;
    }

    public boolean isEnabledForHistoryReferences (List<HistoryReference> hrefs) {
    	// Can Override if required 
    	if (hrefs.size() == 0) {
    		return false;
    	} else if (hrefs.size() > 1 && ! multiSelect) {
    		return false;
    	}
    	for (HistoryReference href : hrefs) {
    		if (! this.isEnabledForHistoryReference(href)) {
    			return false;
    		}
    	}
    	return true;
    }

    public boolean isEnabledForHistoryReference (HistoryReference href) {
    	// Can Override if required 
    	return href != null && href.getHistoryType() != HistoryReference.TYPE_TEMPORARY;
    }

    public void performActions (List<HistoryReference> hrefs) throws Exception {
    	// Can Override if required 
    	for (HistoryReference href : hrefs) {
    		this.performAction(href);
    	}
    }

    public abstract void performAction (HistoryReference href) throws Exception;

    public abstract boolean isEnableForInvoker(Invoker invoker);

}