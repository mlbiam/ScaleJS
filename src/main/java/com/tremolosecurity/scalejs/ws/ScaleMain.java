/*******************************************************************************
 * Copyright 2016 Tremolo Security, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.tremolosecurity.scalejs.ws;

import static org.apache.directory.ldap.client.api.search.FilterBuilder.equal;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;

import javax.servlet.http.HttpSession;

import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.novell.ldap.LDAPAttribute;
import com.novell.ldap.LDAPEntry;
import com.novell.ldap.LDAPException;
import com.novell.ldap.LDAPSearchResults;
import com.tremolosecurity.config.util.ConfigManager;
import com.tremolosecurity.config.xml.ApplicationType;
import com.tremolosecurity.config.xml.AzRuleType;
import com.tremolosecurity.config.xml.OrgType;
import com.tremolosecurity.config.xml.ReportType;
import com.tremolosecurity.config.xml.ReportsType;
import com.tremolosecurity.config.xml.WorkflowType;
import com.tremolosecurity.provisioning.core.ProvisioningException;
import com.tremolosecurity.provisioning.service.util.ApprovalDetails;
import com.tremolosecurity.provisioning.service.util.ApprovalSummaries;
import com.tremolosecurity.provisioning.service.util.ApprovalSummary;
import com.tremolosecurity.provisioning.service.util.Organization;
import com.tremolosecurity.provisioning.service.util.ReportInformation;
import com.tremolosecurity.provisioning.service.util.ReportsList;
import com.tremolosecurity.provisioning.service.util.ServiceActions;
import com.tremolosecurity.provisioning.service.util.TremoloUser;
import com.tremolosecurity.provisioning.service.util.WFCall;
import com.tremolosecurity.provisioning.service.util.WFDescription;
import com.tremolosecurity.provisioning.workflow.ApprovalData;
import com.tremolosecurity.proxy.ProxySys;
import com.tremolosecurity.proxy.auth.AuthController;
import com.tremolosecurity.proxy.auth.AuthInfo;
import com.tremolosecurity.proxy.auth.AzSys;
import com.tremolosecurity.proxy.az.AzRule;
import com.tremolosecurity.proxy.filter.HttpFilter;
import com.tremolosecurity.proxy.filter.HttpFilterChain;
import com.tremolosecurity.proxy.filter.HttpFilterConfig;
import com.tremolosecurity.proxy.filter.HttpFilterRequest;
import com.tremolosecurity.proxy.filter.HttpFilterResponse;
import com.tremolosecurity.proxy.util.ProxyConstants;
import com.tremolosecurity.saml.Attribute;
import com.tremolosecurity.scalejs.cfg.ScaleAttribute;
import com.tremolosecurity.scalejs.cfg.ScaleConfig;
import com.tremolosecurity.scalejs.data.ScaleApprovalData;
import com.tremolosecurity.scalejs.data.ScaleError;
import com.tremolosecurity.scalejs.data.UserData;
import com.tremolosecurity.scalejs.data.WorkflowRequest;
import com.tremolosecurity.server.GlobalEntries;

public class ScaleMain implements HttpFilter {

	static Logger logger = Logger.getLogger(ScaleMain.class.getName());
	
	ScaleConfig scaleConfig;
	ApplicationType appType;
	
	
	@Override
	public void doFilter(HttpFilterRequest request, HttpFilterResponse response, HttpFilterChain chain)
			throws Exception {
		Gson gson = new Gson();
		
		
		
		
		
		if (request.getRequestURI().endsWith("/main/config")) {
			response.setContentType("application/json");
			response.getWriter().println(gson.toJson(scaleConfig).trim());
		} else if (request.getMethod().equalsIgnoreCase("GET") && request.getRequestURI().endsWith("/main/user")) {
			lookupUser(request, response, gson);
		} else if (request.getMethod().equalsIgnoreCase("PUT") && request.getRequestURI().endsWith("/main/user")) {
			saveUser(request, response, gson);
		}  else if (request.getMethod().equalsIgnoreCase("GET") && request.getRequestURI().endsWith("/main/orgs")) {
			AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();			
			AzSys az = new AzSys();			
			OrgType ot = GlobalEntries.getGlobalEntries().getConfigManager().getCfg().getProvisioning().getOrg();
			Organization org = new Organization();
			copyOrg(org,ot,az,userData);
			response.setContentType("application/json");
			response.getWriter().println(gson.toJson(org).trim());
			
		} else if (request.getMethod().equalsIgnoreCase("GET") && request.getRequestURI().contains("/main/workflows/org/")) {
			loadWorkflows(request, response, gson);
			
		} else if (request.getMethod().equalsIgnoreCase("PUT") && request.getRequestURI().endsWith("/main/workflows")) {
			executeWorkflows(request, response, gson);
		} else if (request.getMethod().equalsIgnoreCase("GET") && request.getRequestURI().endsWith("/main/approvals")) {
			AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();
			String uid = userData.getAttribs().get(this.scaleConfig.getUidAttributeName()).getValues().get(0); 			
			response.setContentType("application/json");
			response.getWriter().println(gson.toJson(ServiceActions.listOpenApprovals(uid)).trim());			
		} else if (request.getMethod().equalsIgnoreCase("GET") && request.getRequestURI().contains("/main/approvals/")) {
			loadApproval(request, response, gson);
			
						
		} else if (request.getMethod().equalsIgnoreCase("PUT") && request.getRequestURI().contains("/main/approvals/")) {
			int approvalID = Integer.parseInt(request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1));
			AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();
			String uid = userData.getAttribs().get(this.scaleConfig.getUidAttributeName()).getValues().get(0);
			boolean ok = false;
			ApprovalSummaries summaries = ServiceActions.listOpenApprovals(uid);
			for (ApprovalSummary as : summaries.getApprovals()) {
				if (as.getApproval() == approvalID) {
					ok = true;
				}
			}
			
			if (! ok) {
				response.setStatus(401);
				response.setContentType("application/json");
				ScaleError error = new ScaleError();
				error.getErrors().add("Unauthorized");
				response.getWriter().print(gson.toJson(error).trim());
				response.getWriter().flush();
			} else {
				ScaleApprovalData approvalData = gson.fromJson(new String((byte[]) request.getAttribute(ProxySys.MSG_BODY)), ScaleApprovalData.class);
				try {
					GlobalEntries.getGlobalEntries().getConfigManager().getProvisioningEngine().doApproval(approvalID, uid, approvalData.isApproved(),approvalData.getReason());
				} catch (Exception e) {
					logger.error("Could not execute approval",e);
					response.setStatus(500);
					ScaleError error = new ScaleError();
					error.getErrors().add("There was a problem completeding your request, please contact your system administrator");
					response.getWriter().print(gson.toJson(error).trim());
					response.getWriter().flush();
				}
			}
			
						
		} else if (request.getMethod().equalsIgnoreCase("GET") && request.getRequestURI().contains("/main/reports/org/")) {
			loadReports(request, response, gson);
			
		}
		
		
		else {
			response.setStatus(500);
			ScaleError error = new ScaleError();
			error.getErrors().add("Operation not supported");
			response.getWriter().print(gson.toJson(error).trim());
			response.getWriter().flush();
		}

	}


	private void loadApproval(HttpFilterRequest request, HttpFilterResponse response, Gson gson)
			throws ProvisioningException, IOException, LDAPException {
		int approvalID = Integer.parseInt(request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1));
		AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();
		String uid = userData.getAttribs().get(this.scaleConfig.getUidAttributeName()).getValues().get(0);
		boolean ok = false;
		ApprovalSummaries summaries = ServiceActions.listOpenApprovals(uid);
		for (ApprovalSummary as : summaries.getApprovals()) {
			if (as.getApproval() == approvalID) {
				ok = true;
			}
		}
		
		if (! ok) {
			response.setStatus(401);
			response.setContentType("application/json");
			ScaleError error = new ScaleError();
			error.getErrors().add("Unauthorized");
			response.getWriter().print(gson.toJson(error).trim());
			response.getWriter().flush();
		} else {
			response.setContentType("application/json");
			
			
			ApprovalDetails details = ServiceActions.loadApprovalDetails(uid, approvalID);
			
			String filter = equal(this.scaleConfig.getUidAttributeName(),details.getUserObj().getUserID()).toString();
			ArrayList<String> attrs = new ArrayList<String>();
			for (String attrName : this.scaleConfig.getApprovalAttributes().keySet()) {
				attrs.add(attrName);
			}
			
			if (this.scaleConfig.getRoleAttribute() != null && ! this.scaleConfig.getRoleAttribute().isEmpty()) {
				attrs.add(this.scaleConfig.getRoleAttribute());
			}
			
			LDAPSearchResults res = GlobalEntries.getGlobalEntries().getConfigManager().getMyVD().search("o=Tremolo", 2, filter, attrs);
			
			if (res.hasMore()) {
				LDAPEntry entry = res.next();
				details.getUserObj().getAttribs().clear();
				
				for (String attrName : this.scaleConfig.getApprovalAttributes().keySet()) {
					LDAPAttribute attr = entry.getAttribute(attrName);
					if (attr != null) {
						details.getUserObj().getAttribs().put(scaleConfig.getApprovalAttributes().get(attrName).getDisplayName(), new Attribute(scaleConfig.getApprovalAttributes().get(attrName).getDisplayName(),attr.getStringValue()));
					}
				}
				
				if (this.scaleConfig.getRoleAttribute() != null && ! this.scaleConfig.getRoleAttribute().isEmpty()) {
					LDAPAttribute attr = entry.getAttribute(this.scaleConfig.getRoleAttribute());
					if (attr != null) {
						details.getUserObj().getGroups().clear();
						for (String val : attr.getStringValueArray()) {
							details.getUserObj().getGroups().add(val);
						}
					}
				} else {
					details.getUserObj().getGroups().clear();
					ArrayList<String> attrNames = new ArrayList<String>();
					attrNames.add("cn");
					LDAPSearchResults res2 = GlobalEntries.getGlobalEntries().getConfigManager().getMyVD().search("o=Tremolo", 2, equal("uniqueMember",entry.getDN()).toString(), attrNames);
					
					while (res2.hasMore()) {
						LDAPEntry entry2 = res2.next();
						LDAPAttribute la = entry2.getAttribute("cn");
						if (la != null) {
							details.getUserObj().getGroups().add(la.getStringValue());
						}
					}
				}
				
				
			}
			
			while (res.hasMore()) res.next();
			
					
			response.getWriter().println(gson.toJson(details).trim());
			response.getWriter().flush();
		}
	}


	private void executeWorkflows(HttpFilterRequest request, HttpFilterResponse response, Gson gson)
			throws MalformedURLException, ProvisioningException, IOException {
		Type listType = new TypeToken<ArrayList<WorkflowRequest>>() {}.getType();
		List<WorkflowRequest> reqs = gson.fromJson(new String((byte[])request.getAttribute(ProxySys.MSG_BODY)), listType);
		HashMap<String,String> results = new HashMap<String,String>();
		
		for (WorkflowRequest req : reqs) {
			if (req.getReason() == null || req.getReason().isEmpty()) {
				results.put(req.getName(), "Reason is required");
			} else {
				
				HashSet<String> allowedOrgs = new HashSet<String>();
				AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();
				OrgType ot = GlobalEntries.getGlobalEntries().getConfigManager().getCfg().getProvisioning().getOrg();
				AzSys az = new AzSys();			
				this.checkOrg(allowedOrgs, ot, az, userData, request.getSession());
				
				String orgid = null;
				
				List<WorkflowType> wfs = GlobalEntries.getGlobalEntries().getConfigManager().getCfg().getProvisioning().getWorkflows().getWorkflow();
				for (WorkflowType wf : wfs) {
					if (wf.getName().equals(req.getName())) {
						orgid = wf.getOrgid();
						break;
					}
				}
				
				if (orgid == null) {
					results.put(req.getName(), "Not Found");
				} else if (! allowedOrgs.contains(orgid)) {
					results.put(req.getName(), "Unauthorized");
				} else {
					WFCall wfCall = new WFCall();
					wfCall.setName(req.getName());
					wfCall.setReason(req.getReason());
					wfCall.setUidAttributeName(this.scaleConfig.getUidAttributeName());
					
					TremoloUser tu = new TremoloUser();
					tu.setUid(userData.getAttribs().get(this.scaleConfig.getUidAttributeName()).getValues().get(0));
					tu.getAttributes().add(new Attribute(this.scaleConfig.getUidAttributeName(),userData.getAttribs().get(this.scaleConfig.getUidAttributeName()).getValues().get(0)));
					
					wfCall.setUser(tu);
					
					try {
						com.tremolosecurity.provisioning.workflow.ExecuteWorkflow exec = new com.tremolosecurity.provisioning.workflow.ExecuteWorkflow();
						exec.execute(wfCall, GlobalEntries.getGlobalEntries().getConfigManager(), null);
						results.put(req.getName(), "success");
					} catch (Exception e) {
						logger.error("Could not update user",e);
						results.put(req.getName(), "Error, please contact your system administrator");
					}
				}
				
				
			}
		}
		
		response.setContentType("application/json");
		response.getWriter().println(gson.toJson(results).trim());
	}


	private void loadWorkflows(HttpFilterRequest request, HttpFilterResponse response, Gson gson)
			throws MalformedURLException, ProvisioningException, IOException {
		String orgid = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
		ConfigManager cfgMgr = GlobalEntries.getGlobalEntries().getConfigManager();
		HashSet<String> allowedOrgs = new HashSet<String>();
		AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();
		OrgType ot = GlobalEntries.getGlobalEntries().getConfigManager().getCfg().getProvisioning().getOrg();
		AzSys az = new AzSys();			
		this.checkOrg(allowedOrgs, ot, az, userData, request.getSession());
		
		if (! allowedOrgs.contains(orgid)) {
			response.setStatus(401);
			response.setContentType("application/json");
			ScaleError error = new ScaleError();
			error.getErrors().add("Unauthorized");
			response.getWriter().print(gson.toJson(error).trim());
			response.getWriter().flush();
		} else {
			List<WorkflowType> wfs = GlobalEntries.getGlobalEntries().getConfigManager().getCfg().getProvisioning().getWorkflows().getWorkflow();
			
			ArrayList<WFDescription> workflows = new ArrayList<WFDescription>();
			
			for (WorkflowType wf : wfs) {
				
				if (wf.isInList() != null && wf.isInList().booleanValue()) {
					
					if ( wf.getOrgid() == null || wf.getOrgid().equalsIgnoreCase(orgid)) { 
					
						WFDescription desc = new WFDescription();
						
						desc.setName(wf.getName());
						desc.setLabel(wf.getLabel());
						desc.setDescription(wf.getDescription());
						
						
						workflows.add(desc);
					}
				}
				
			}
			
			response.setContentType("application/json");
			response.getWriter().println(gson.toJson(workflows).trim());
			response.getWriter().flush();
		}
	}
	
	private void loadReports(HttpFilterRequest request, HttpFilterResponse response, Gson gson)
			throws MalformedURLException, ProvisioningException, IOException {
		String orgid = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
		ConfigManager cfgMgr = GlobalEntries.getGlobalEntries().getConfigManager();
		HashSet<String> allowedOrgs = new HashSet<String>();
		AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();
		OrgType ot = GlobalEntries.getGlobalEntries().getConfigManager().getCfg().getProvisioning().getOrg();
		AzSys az = new AzSys();			
		this.checkOrg(allowedOrgs, ot, az, userData, request.getSession());
		
		if (! allowedOrgs.contains(orgid)) {
			response.setStatus(401);
			response.setContentType("application/json");
			ScaleError error = new ScaleError();
			error.getErrors().add("Unauthorized");
			response.getWriter().print(gson.toJson(error).trim());
			response.getWriter().flush();
		} else {
			
			ReportsType reports = GlobalEntries.getGlobalEntries().getConfigManager().getCfg().getProvisioning().getReports();
			
			ReportsList reportsList = new ReportsList();
			reportsList.setReports(new ArrayList<ReportInformation>());
			
			
			for (ReportType report : reports.getReport()) {
				if (report.getOrgID().equals(orgid)) {
					ReportInformation ri = new ReportInformation();
					ri.setName(report.getName());
					ri.setDescription(report.getDescription());
					ri.setOrgID(report.getOrgID());
					ri.setParameters(new ArrayList<String>());
					ri.getParameters().addAll(report.getParamater());
					
					reportsList.getReports().add(ri);
				}
			}
			
			
			response.setContentType("application/json");
			response.getWriter().println(gson.toJson(reportsList).trim());
			response.getWriter().flush();
		}
	}
	
	
	private boolean copyOrg(Organization org,OrgType ot, AzSys az, AuthInfo auinfo) throws MalformedURLException, ProvisioningException {
		
		ConfigManager cfgMgr = GlobalEntries.getGlobalEntries().getConfigManager();
		
		if (ot.getAzRules() != null && ot.getAzRules().getRule().size() > 0) {
			ArrayList<AzRule> rules = new ArrayList<AzRule>();
			
			for (AzRuleType art : ot.getAzRules().getRule()) {
				rules.add(new AzRule(art.getScope(),art.getConstraint(),art.getClassName(),cfgMgr,null));
			}
			
			
			if (! az.checkRules(auinfo,cfgMgr , rules)) {
				return false;
			}
		}
		
		org.setId(ot.getUuid());
		org.setName(ot.getName());
		org.setDescription(ot.getDescription());
		
		for (OrgType child : ot.getOrgs()) {
			Organization sub = new Organization();
			
			if (copyOrg(sub,child, az, auinfo)) {
				org.getSubOrgs().add(sub);
			}
		}
		
		return true;
	}

	private void saveUser(HttpFilterRequest request, HttpFilterResponse response, Gson gson) throws IOException {
		ScaleError errors = new ScaleError();
		String json = new String( (byte[]) request.getAttribute(ProxySys.MSG_BODY));
		
		

		
		JsonElement root = new JsonParser().parse(json);
		JsonObject jo = root.getAsJsonObject();
		
		HashMap<String,String> values = new HashMap<String,String>();
		boolean ok = true;
		
		for (Entry<String,JsonElement> entry : jo.entrySet()) {
			String attributeName = entry.getKey();
			String value = entry.getValue().getAsJsonObject().get("value").getAsString();
			
			
			
			if (this.scaleConfig.getAttributes().get(attributeName) == null) {
				errors.getErrors().add("Invalid attribute : '" + attributeName + "'");
				ok = false;
			} else if (this.scaleConfig.getAttributes().get(attributeName).isReadOnly()) {
				errors.getErrors().add("Attribute is read only : '" + this.scaleConfig.getAttributes().get(attributeName).getDisplayName() + "'");
				ok = false;
			} else if (this.scaleConfig.getAttributes().get(attributeName).isRequired() && value.length() == 0) {
				errors.getErrors().add("Attribute is required : '" + this.scaleConfig.getAttributes().get(attributeName).getDisplayName() + "'");
				ok = false;
			} else if (this.scaleConfig.getAttributes().get(attributeName).getMinChars() > 0 && this.scaleConfig.getAttributes().get(attributeName).getMinChars() <= value.length()) {
				errors.getErrors().add(this.scaleConfig.getAttributes().get(attributeName).getDisplayName() + " must have at least " + this.scaleConfig.getAttributes().get(attributeName).getMinChars() + " characters");
				ok = false;
			} else if (this.scaleConfig.getAttributes().get(attributeName).getMaxChars() > 0 && this.scaleConfig.getAttributes().get(attributeName).getMaxChars() >= value.length()) {
				errors.getErrors().add(this.scaleConfig.getAttributes().get(attributeName).getDisplayName() + " must have at most " + this.scaleConfig.getAttributes().get(attributeName).getMaxChars() + " characters");
				ok = false;
			} else if (this.scaleConfig.getAttributes().get(attributeName).getPattern() != null) {
				try {
					Matcher m = this.scaleConfig.getAttributes().get(attributeName).getPattern().matcher(value);
					if (m == null || ! m.matches()) {
						ok = false;
					}
				} catch (Exception e) {
					ok = false;
				}
				
				if (!ok) {
					errors.getErrors().add("Attribute value not valid : '" + this.scaleConfig.getAttributes().get(attributeName).getDisplayName() + "' - " + this.scaleConfig.getAttributes().get(attributeName).getRegExFailedMsg());
				}
			}
			
			values.put(attributeName, value);
		}

		for (String attrName : this.scaleConfig.getAttributes().keySet()) {
			if (this.scaleConfig.getAttributes().get(attrName).isRequired() && ! values.containsKey(attrName)) {
				errors.getErrors().add("Attribute is required : '" + this.scaleConfig.getAttributes().get(attrName).getDisplayName() + "'");
				ok = false;
			}
		}
		
		if (ok) {
			AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();
			
			ConfigManager cfgMgr = GlobalEntries.getGlobalEntries().getConfigManager();
			WFCall wfCall = new WFCall();
			wfCall.setName(this.scaleConfig.getWorkflowName());
			wfCall.setReason("User update");
			wfCall.setUidAttributeName(this.scaleConfig.getUidAttributeName());
			
			TremoloUser tu = new TremoloUser();
			tu.setUid(userData.getAttribs().get(this.scaleConfig.getUidAttributeName()).getValues().get(0));
			for (String name : values.keySet()) {
				tu.getAttributes().add(new Attribute(name,values.get(name)));
			}
			
			tu.getAttributes().add(new Attribute(this.scaleConfig.getUidAttributeName(),userData.getAttribs().get(this.scaleConfig.getUidAttributeName()).getValues().get(0)));
			
			wfCall.setUser(tu);
			
			try {
				com.tremolosecurity.provisioning.workflow.ExecuteWorkflow exec = new com.tremolosecurity.provisioning.workflow.ExecuteWorkflow();
				exec.execute(wfCall, GlobalEntries.getGlobalEntries().getConfigManager(), null);
				lookupUser(request, response, gson);
			} catch (Exception e) {
				logger.error("Could not update user",e);
				response.setStatus(500);
				ScaleError error = new ScaleError();
				error.getErrors().add("Please contact your system administrator");
				response.getWriter().print(gson.toJson(error).trim());
				response.getWriter().flush();
			}
			
			
		} else {
			response.setStatus(500);
			
			response.getWriter().print(gson.toJson(errors).trim());
			response.getWriter().flush();
		}
	}

	private void lookupUser(HttpFilterRequest request, HttpFilterResponse response, Gson gson)
			throws LDAPException, IOException {
		response.setContentType("application/json");
		
		AuthInfo userData = ((AuthController) request.getSession().getAttribute(ProxyConstants.AUTH_CTL)).getAuthInfo();
		
		UserData userToSend = new UserData();
		userToSend.setDn(userData.getUserDN());
		
		for (String attrName : this.scaleConfig.getAttributes().keySet()) {
			
			
			Attribute attr = new Attribute(attrName);
			Attribute fromUser = userData.getAttribs().get(attrName);
			if (fromUser != null) {
				attr.getValues().addAll(fromUser.getValues());
				
				if (attrName.equalsIgnoreCase(this.scaleConfig.getUidAttributeName())) {
					userToSend.setUid(fromUser.getValues().get(0));
				}
			}
			userToSend.getAttributes().add(attr);
		}
		
		if (this.scaleConfig.getRoleAttribute() != null && ! this.scaleConfig.getRoleAttribute().isEmpty()) {
			Attribute fromUser = userData.getAttribs().get(this.scaleConfig.getRoleAttribute());
			Attribute attr = new Attribute(this.scaleConfig.getRoleAttribute());
			if (fromUser != null) {
				attr.getValues().addAll(fromUser.getValues());
			}
			
			userToSend.getAttributes().add(attr);
		}
		
		ArrayList<String> attrNames = new ArrayList<String>();
		attrNames.add("cn");
		LDAPSearchResults res = GlobalEntries.getGlobalEntries().getConfigManager().getMyVD().search("o=Tremolo", 2, equal("uniqueMember",userData.getUserDN()).toString(), attrNames);
		
		while (res.hasMore()) {
			LDAPEntry entry = res.next();
			LDAPAttribute la = entry.getAttribute("cn");
			if (la != null) {
				userToSend.getGroups().add(la.getStringValue());
			}
		}
		
		response.getWriter().println(gson.toJson(userToSend).trim());
	}

	@Override
	public void filterResponseText(HttpFilterRequest request, HttpFilterResponse response, HttpFilterChain chain,
			StringBuffer data) throws Exception {
		

	}

	@Override
	public void filterResponseBinary(HttpFilterRequest request, HttpFilterResponse response, HttpFilterChain chain,
			byte[] data, int length) throws Exception {
		

	}

	
	private String loadAttributeValue(String name,String label,HttpFilterConfig config) throws Exception {
		Attribute attr = config.getAttribute(name);
		if (attr == null) {
			throw new Exception(label + " not found");
		}
		
		String val = attr.getValues().get(0);
		logger.info(label + ": '" + val + "'");
		
		return val;
	}
	
	private String loadOptionalAttributeValue(String name,String label,HttpFilterConfig config) throws Exception {
		Attribute attr = config.getAttribute(name);
		if (attr == null) {
			logger.warn(label + " not found");
			return null;
		}
		
		String val = attr.getValues().get(0);
		logger.info(label + ": '" + val + "'");
		
		return val;
	}
	
	@Override
	public void initFilter(HttpFilterConfig config) throws Exception {
		this.scaleConfig = new ScaleConfig();
		scaleConfig.setDisplayNameAttribute(this.loadAttributeValue("displayNameAttribute", "Display Name Attribute Name", config));
		scaleConfig.getFrontPage().setTitle(this.loadAttributeValue("frontPage.title", "Front Page Title", config));
		scaleConfig.getFrontPage().setText(this.loadAttributeValue("frontPage.text", "Front Page Text", config));
		scaleConfig.setCanEditUser(this.loadAttributeValue("canEditUser", "User Fields Editable", config).equalsIgnoreCase("true"));
		scaleConfig.setWorkflowName(this.loadAttributeValue("workflowName", "Save User Workflow", config));
		scaleConfig.setUidAttributeName(this.loadAttributeValue("uidAttributeName", "User ID Attribute Name", config));
		String val = this.loadOptionalAttributeValue("roleAttribute", "Role Attribute Name", config);
				
		
		this.appType = new ApplicationType();
		this.appType.setAzTimeoutMillis((long) 3000);
		
		if (val != null) {
			scaleConfig.setRoleAttribute(val);
		}
		
		Attribute attr = config.getAttribute("attributeNames");
		if (attr == null) {
			throw new Exception("Attribute names not found");
		}
		
		for (String attributeName : attr.getValues()) {
			ScaleAttribute scaleAttr = new ScaleAttribute();
			scaleAttr.setName(attributeName);
			scaleAttr.setDisplayName(this.loadAttributeValue(attributeName + ".displayName", attributeName + " Display Name", config));
			scaleAttr.setReadOnly(this.loadAttributeValue(attributeName + ".readOnly", attributeName + " Read Only", config).equalsIgnoreCase("true"));
			
			val = this.loadOptionalAttributeValue(attributeName + ".required", attributeName + " Required", config);
			scaleAttr.setRequired(val != null && val.equalsIgnoreCase("true"));
			
			val = this.loadOptionalAttributeValue(attributeName + ".regEx", attributeName + " Reg Ex", config);
			if (val != null) {
				scaleAttr.setRegEx(val);
			}
			
			val = this.loadOptionalAttributeValue(attributeName + ".regExFailedMsg", attributeName + " Reg Ex Failed Message", config);
			if (val != null) {
				scaleAttr.setRegExFailedMsg(val);
			}
			
			val = this.loadOptionalAttributeValue(attributeName + ".minChars", attributeName + " Minimum Characters", config);
			if (val != null) {
				scaleAttr.setMinChars(Integer.parseInt(val));
			}
			
			val = this.loadOptionalAttributeValue(attributeName + ".mxnChars", attributeName + " Maximum Characters", config);
			if (val != null) {
				scaleAttr.setMaxChars(Integer.parseInt(val));
			}
			
			
			scaleConfig.getAttributes().put(attributeName, scaleAttr);
		}
		
		
		attr = config.getAttribute("approvalAttributeNames");
		if (attr == null) {
			throw new Exception("Approval attribute names not found");
		}
		
		for (String attributeName : attr.getValues()) {
			ScaleAttribute scaleAttr = new ScaleAttribute();
			scaleAttr.setName(attributeName);
			scaleAttr.setDisplayName(this.loadAttributeValue("approvals." + attributeName, "Approvals attribute " + attributeName + " Display Name", config));
			scaleConfig.getApprovalAttributes().put(attributeName, scaleAttr);
		}
		
	}
	
	private void checkOrg(HashSet<String> allowedOrgs,OrgType ot, AzSys az, AuthInfo auinfo,HttpSession session) throws MalformedURLException, ProvisioningException {
		ConfigManager cfgMgr = GlobalEntries.getGlobalEntries().getConfigManager();
		
		if (ot.getAzRules() != null && ot.getAzRules().getRule().size() > 0) {
			ArrayList<AzRule> rules = new ArrayList<AzRule>();
			
			for (AzRuleType art : ot.getAzRules().getRule()) {
				rules.add(new AzRule(art.getScope(),art.getConstraint(),art.getClassName(),cfgMgr,null));
			}
			
			
			if (! az.checkRules(auinfo, cfgMgr, rules,session, this.appType)) {
				return;
			}
		}
		
		allowedOrgs.add(ot.getUuid());
		
		for (OrgType child : ot.getOrgs()) {
			checkOrg(allowedOrgs,child, az, auinfo,session);
		}
	}

}
